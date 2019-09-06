package com.artezio.bpm.services;

import com.artezio.bpm.rest.dto.repository.ProcessDefinitionRepresentation;
import com.artezio.bpm.rest.dto.task.TaskRepresentation;
import com.artezio.bpm.services.exceptions.NotAuthorizedException;
import com.artezio.bpm.validation.VariableValidator;
import com.artezio.formio.client.exceptions.FormNotFoundException;
import com.artezio.logging.Log;
import io.swagger.v3.oas.annotations.Operation;
import org.camunda.bpm.engine.FormService;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.form.FormData;
import org.camunda.bpm.engine.form.StartFormData;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.camunda.bpm.engine.task.IdentityLink;
import org.camunda.bpm.engine.task.IdentityLinkType;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import javax.annotation.security.PermitAll;
import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.artezio.bpm.services.VariablesMapper.EXTENSION_NAME_PREFIX;
import static com.artezio.logging.Log.Level.CONFIG;
import static java.util.Collections.emptyMap;

@Path("/process-definition")
@Stateless
public class ProcessDefinitionSvc {

    @Inject
    private IdentitySvc identityService;
    @Inject
    private FormService camundaFormService;
    @Inject
    private FormSvc formService;
    @Inject
    private RuntimeService runtimeService;
    @Inject
    private RepositoryService repositoryService;
    @Inject
    private VariablesMapper variablesMapper;
    @Inject
    private TaskSvc taskService;
    @Inject
    private VariableValidator variableValidator;

    @PermitAll
    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
    @Log(level = CONFIG, beforeExecuteMessage = "Getting list of startable by caller process definitions")
    public List<ProcessDefinitionRepresentation> listStartableByUser() {
        return repositoryService
                .createProcessDefinitionQuery()
                .latestVersion()
                .list()
                .stream()
                .filter(this::isStartableByUser)
                .map(ProcessDefinitionRepresentation::fromProcessDefinition)
                .collect(Collectors.toList());
    }

    @POST
    @Path("/key/{process-definition-key}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @Operation(description = "Start a process instance using input variables. If an execution doesn't have some of input variables, they are ignored. " +
            "Note: if two or more files with identical names are uploaded in one variable, the only one of these " +
            "files will be used and it is not determined which one will be chosen." +
            "Returns next assigned task if available")
    @Log(level = CONFIG, beforeExecuteMessage = "Starting process '{0}'", afterExecuteMessage = "Process '{0}' successfully started")
    public TaskRepresentation start(
            @PathParam("process-definition-key") @Valid @NotNull String processDefinitionKey,
            Map<String, Object> inputVariables) throws IOException {
        ProcessDefinition processDefinition = getLastProcessDefinition(processDefinitionKey);
        ensureStartableByUser(processDefinition);
        ProcessInstance processInstance = processDefinition.hasStartFormKey()
                ? startProcessByFormSubmission(processDefinition, inputVariables)
                : startProcess(processDefinition, inputVariables);
        return TaskRepresentation.fromEntity(taskService.getNextAssignedTask(processInstance.getProcessInstanceId()));
    }

    @GET
    @Path("/key/{process-definition-key}/form")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @Log(beforeExecuteMessage = "Loading start form for process '{0}'", afterExecuteMessage = "Start form for process '{0}' successfully loaded")
    public String loadStartForm(
            @PathParam("process-definition-key") @Valid @NotNull String processDefinitionKey) {
        ProcessDefinition processDefinition = getLastProcessDefinition(processDefinitionKey);
        ensureStartableByUser(processDefinition);
        FormData formData = camundaFormService.getStartFormData(processDefinition.getId());
        Map<String, Object> startFormVariables = getStartFormVariables(formData);
        return formService.getStartFormWithData(processDefinition.getId(), startFormVariables);
    }

    protected boolean isStartableByUser(ProcessDefinition processDefinition) {
        List<IdentityLink> links = repositoryService.getIdentityLinksForProcessDefinition(processDefinition.getId());
        return userHasAccess(links);
    }

    protected boolean userHasAccess(List<IdentityLink> links) {
        return userIsInCandidateGroup(links)
                || userIsCandidate(links);
    }

    protected boolean userIsCandidate(List<IdentityLink> links) {
        return links.stream()
                .filter(identityLink -> identityLink.getUserId() != null)
                .filter(identityLink -> identityLink.getUserId().equals(identityService.userId()))
                .anyMatch(identityLink -> identityLink.getType().equals(IdentityLinkType.CANDIDATE));
    }

    protected boolean userIsInCandidateGroup(List<IdentityLink> links) {
        return links.stream()
                .filter(identityLink -> identityLink.getGroupId() != null)
                .filter(identityLink -> identityLink.getType().equals(IdentityLinkType.CANDIDATE))
                .map(IdentityLink::getGroupId)
                .anyMatch(role -> identityService.userGroups().contains(role));
    }

    private Map<String, String> getProcessExtensions(ProcessDefinition processDefinition) {
        String processDefinitionId = processDefinition.getId();
        String processDefinitionKey = processDefinition.getKey();
        BpmnModelInstance bpmnModelInstance = repositoryService.getBpmnModelInstance(processDefinitionId);
        Process processElement = bpmnModelInstance.getModelElementById(processDefinitionKey);
        ExtensionElements extensionElements = processElement.getExtensionElements();
        return extensionElements != null
                ? extensionElements.getElements().stream()
                .flatMap(extensionElement -> ((CamundaProperties) extensionElement).getCamundaProperties().stream())
                .filter(extension -> extension.getCamundaName().startsWith(EXTENSION_NAME_PREFIX))
                .collect(Collectors.toMap(CamundaProperty::getCamundaName, CamundaProperty::getCamundaValue))
                : emptyMap();
    }

    private ProcessInstance startProcess(ProcessDefinition processDefinition, Map<String, Object> inputVariables) {
        Map<String, String> processExtensions = getProcessExtensions(processDefinition);
        inputVariables = variablesMapper.convertVariablesToEntities(inputVariables, processExtensions);
        variableValidator.validate(inputVariables);
        return runtimeService.startProcessInstanceByKey(processDefinition.getKey(), inputVariables);
    }

    private ProcessInstance startProcessByFormSubmission(ProcessDefinition processDefinition, Map<String, Object> variables) throws IOException, FormNotFoundException {
        Map<String, Object> mergedVariables = validateAndMergeToFormVariables(variables, processDefinition.getId());
        Map<String, String> processExtensions = getProcessExtensions(processDefinition);
        mergedVariables = variablesMapper.convertVariablesToEntities(mergedVariables, processExtensions);
        variableValidator.validate(mergedVariables);
        return camundaFormService.submitStartForm(processDefinition.getId(), mergedVariables);
    }

    protected Map<String, Object> validateAndMergeToFormVariables(Map<String, Object> inputVariables,
                                                                  String processDefinitionId) throws IOException, FormNotFoundException {
        StartFormData formData = camundaFormService.getStartFormData(processDefinitionId);
        String formKey = formData.getFormKey();
        if (formKey == null) {
            throw new RuntimeException("Process has no start form");
        } else {
            String validatedVariablesJson = formService.dryValidationAndCleanupStartForm(processDefinitionId, inputVariables);
            Map<String, Object> formVariables = getStartFormVariables(formData);
            variablesMapper.updateVariables(formVariables, validatedVariablesJson);
            return formVariables;
        }
    }

    protected void ensureStartableByUser(ProcessDefinition processDefinition) {
        if (!isStartableByUser(processDefinition)) {
            throw new NotAuthorizedException();
        }
    }

    protected Map<String, Object> getStartFormVariables(FormData formData) {
        return formData
                .getFormFields()
                .stream()
                .collect(HashMap::new,
                        (map, formField) -> map.put(formField.getId(), formField.getValue().getValue()),
                        HashMap::putAll);
    }

    protected ProcessDefinition getLastProcessDefinition(String processDefinitionKey) {
        return repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .processDefinitionKey(processDefinitionKey)
                .singleResult();
    }

}
