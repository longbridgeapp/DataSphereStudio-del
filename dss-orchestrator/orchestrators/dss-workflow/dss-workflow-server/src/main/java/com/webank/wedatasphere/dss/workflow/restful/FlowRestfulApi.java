/*
 * Copyright 2019 WeBank
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.webank.wedatasphere.dss.workflow.restful;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.webank.wedatasphere.dss.common.exception.DSSErrorException;
import com.webank.wedatasphere.dss.common.label.DSSLabel;
import com.webank.wedatasphere.dss.common.label.EnvDSSLabel;
import com.webank.wedatasphere.dss.contextservice.service.ContextService;
import com.webank.wedatasphere.dss.contextservice.service.impl.ContextServiceImpl;
import com.webank.wedatasphere.dss.orchestrator.common.protocol.ResponseConvertOrchestrator;
import com.webank.wedatasphere.dss.standard.app.sso.Workspace;
import com.webank.wedatasphere.dss.standard.sso.utils.SSOHelper;
import com.webank.wedatasphere.dss.workflow.WorkFlowManager;
import com.webank.wedatasphere.dss.workflow.common.entity.DSSFlow;
import com.webank.wedatasphere.dss.workflow.constant.DSSWorkFlowConstant;
import com.webank.wedatasphere.dss.workflow.entity.request.*;
import com.webank.wedatasphere.dss.workflow.service.DSSFlowService;
import com.webank.wedatasphere.dss.workflow.service.PublishService;
import org.apache.commons.lang.StringUtils;
import org.apache.linkis.server.Message;
import org.apache.linkis.server.security.SecurityFilter;
import org.codehaus.jackson.map.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping(path = "/dss/workflow", produces = {"application/json"})
public class FlowRestfulApi {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlowRestfulApi.class);

    @Autowired
    private DSSFlowService flowService;
    private ContextService contextService = ContextServiceImpl.getInstance();
    @Autowired
    private PublishService publishService;
    @Autowired
    private WorkFlowManager workFlowManager;
    ObjectMapper mapper = new ObjectMapper();

    @RequestMapping(value = "addFlow",method = RequestMethod.POST)
    public Message addFlow(HttpServletRequest req, @RequestBody AddFlowRequest addFlowRequest) throws DSSErrorException, JsonProcessingException {
        //??????????????????????????????????????????????????????????????????
        String userName = SecurityFilter.getLoginUsername(req);
        // TODO: 2019/5/23 flowName????????????????????????
        String name = addFlowRequest.getName();
        String workspaceName = addFlowRequest.getWorkspaceName();
        String projectName = addFlowRequest.getProjectName();
        String version = addFlowRequest.getVersion();
        String description = addFlowRequest.getDescription();
        Long parentFlowID = addFlowRequest.getParentFlowID();
        String uses = addFlowRequest.getUses();
        List<DSSLabel> dssLabelList = new ArrayList<>();
        String contextId = contextService.createContextID(workspaceName, projectName, name, version, userName);
        DSSFlow dssFlow = workFlowManager.createWorkflow(userName,name,contextId,description,parentFlowID,uses,null,dssLabelList);
        // TODO: 2019/5/16 ??????????????????????????????
        return Message.ok().data("flow", dssFlow);
    }

    @RequestMapping(value = "publishWorkflow",method = RequestMethod.POST)
    public Message publishWorkflow(HttpServletRequest request, @RequestBody PublishWorkflowRequest publishWorkflowRequest) {
        Long workflowId = publishWorkflowRequest.getWorkflowId();
//        Map<String, Object> labels = StreamSupport.stream(Spliterators.spliteratorUnknownSize(dssLabel.getFields(),
//            Spliterator.ORDERED), false).collect(Collectors.toMap(Entry::getKey, entry -> entry.getValue().getTextValue()));
        //todo modify by front label
        String dssLabel = publishWorkflowRequest.getLabels().getRoute();
        Map<String, Object> labels=new HashMap<>();
        labels.put(EnvDSSLabel.DSS_ENV_LABEL_KEY,dssLabel);
        String comment = publishWorkflowRequest.getComment();
        Workspace workspace = SSOHelper.getWorkspace(request);
        String publishUser = SecurityFilter.getLoginUsername(request);
        Message message;
        try{
            String taskId = publishService.submitPublish(publishUser, workflowId, labels, workspace, comment);
            LOGGER.info("submit publish task ok ,taskId is {}.", taskId);
            if (StringUtils.isNotEmpty(taskId)){
                message = Message.ok("?????????????????????????????????").data("releaseTaskId", taskId);
            } else{
                LOGGER.error("taskId {} is error.", taskId);
                message = Message.error("?????????????????????");
            }
        }catch(final Throwable t){
            LOGGER.error("failed to submit publish task for workflow id {}.", workflowId, t);
            message = Message.error("?????????????????????");
        }
        return message;
    }

    /**
     * ????????????????????????
     * @param request
     * @param releaseTaskId
     * @return
     */

    @RequestMapping(value = "getReleaseStatus",method = RequestMethod.GET)
    public Message getReleaseStatus(HttpServletRequest request,
                                    @NotNull(message = "???????????????id????????????") @RequestParam(required = false, name = "releaseTaskId") Long releaseTaskId) {
        String username = SecurityFilter.getLoginUsername(request);
        Message message;
        try {
            ResponseConvertOrchestrator response = publishService.getStatus(username, releaseTaskId.toString());
            if (null != response.getResponse()) {
                String status = response.getResponse().getJobStatus().toString();
                status = StringUtils.isNotBlank(status) ? status.toLowerCase() : status;
                //????????????????????????????????????
                if ("failed".equalsIgnoreCase(status)) {
                    message = Message.error("????????????:" + response.getResponse().getMessage()).data("status", status);
                } else if (StringUtils.isNotBlank(status)) {
                    message = Message.ok("??????????????????").data("status", status);
                } else {
                    LOGGER.error("status is null or empty, failed to get status");
                    message = Message.error("??????????????????");
                }
            } else {
                LOGGER.error("status is null or empty, failed to get status");
                message = Message.error("??????????????????");
            }
        } catch (final Throwable t) {
            LOGGER.error("Failed to get release status for {}", releaseTaskId, t);
            message = Message.error("????????????:" + t.getMessage());
        }
        return message;
    }

    /**
     * ????????????????????????????????????????????????Json,BML?????????
     * @param req
     * @param updateFlowBaseInfoRequest
     * @return
     * @throws DSSErrorException
     */
    @RequestMapping(value = "updateFlowBaseInfo",method = RequestMethod.POST)
//    @ProjectPrivChecker
    public Message updateFlowBaseInfo(HttpServletRequest req,@RequestBody UpdateFlowBaseInfoRequest updateFlowBaseInfoRequest) throws DSSErrorException {
        Long flowID = updateFlowBaseInfoRequest.getId();
        String name = updateFlowBaseInfoRequest.getName();
        String description = updateFlowBaseInfoRequest.getDescription();
        String uses = updateFlowBaseInfoRequest.getUses();
//        ioManager.checkeIsExecuting(projectVersionID);
        // TODO: 2019/6/13  projectVersionID???????????????
        //????????????????????????
        DSSFlow dssFlow = new DSSFlow();
        dssFlow.setId(flowID);
        dssFlow.setName(name);
        dssFlow.setDescription(description);
        dssFlow.setUses(uses);
        flowService.updateFlowBaseInfo(dssFlow);
        return Message.ok();
    }

    /**
     * ??????????????????Json??????????????????????????????
     * @param req
     * @param flowID
     * @return
     * @throws DSSErrorException
     */

    @RequestMapping(value = "get",method = RequestMethod.GET)
    public Message get(HttpServletRequest req, @RequestParam(required = false, name = "flowId") Long flowID
    ) throws DSSErrorException {
        // TODO: 2019/5/23 id????????????
        String username = SecurityFilter.getLoginUsername(req);
        DSSFlow DSSFlow;
        DSSFlow = flowService.getLatestVersionFlow(flowID);
//        if (!username.equals(DSSFlow.getCreator())) {
//            return Message.ok("??????????????????????????????");
//        }
        return Message.ok().data("flow", DSSFlow);
    }

    @RequestMapping(value = "deleteFlow",method = RequestMethod.POST)
//    @ProjectPrivChecker
    public Message deleteFlow(HttpServletRequest req,@RequestBody  DeleteFlowRequest deleteFlowRequest) throws DSSErrorException {
        Long flowID = deleteFlowRequest.getId();
        boolean sure = deleteFlowRequest.getSure() != null && deleteFlowRequest.getSure().booleanValue();
        // TODO: 2019/6/13  projectVersionID???????????????
        //state???true?????????????????????
        if (flowService.getFlowByID(flowID).getState() && !sure) {
            return Message.ok().data("warmMsg", "???????????????????????????????????????????????????????????????????????????????????????????????????");
        }
//        ioManager.checkeIsExecuting(projectVersionID);
        flowService.batchDeleteFlow(Arrays.asList(flowID));
        return Message.ok();
    }

    /**
     * ????????????????????????????????????Json???????????????????????????????????????Json??????
     * @param req
     * @param saveFlowRequest
     * @return
     * @throws DSSErrorException
     * @throws IOException
     */

    @RequestMapping(value = "saveFlow",method = RequestMethod.POST)
//    @ProjectPrivChecker
    public Message saveFlow(HttpServletRequest req, @RequestBody SaveFlowRequest saveFlowRequest) throws DSSErrorException, IOException {
        Long flowID = saveFlowRequest.getId();
        String jsonFlow = saveFlowRequest.getJson();
        String workspaceName = saveFlowRequest.getWorkspaceName();
        String projectName = saveFlowRequest.getProjectName();
        String userName = SecurityFilter.getLoginUsername(req);
        //String comment = json.get("comment") == null?"????????????":json.get("comment").getTextValue();
        //????????????comment????????????,????????????comment
        String comment = null;
//        ioManager.checkeIsExecuting(projectVersionID);
        // TODO: 2020/6/9 ??????cs???bml?????????
        String version = null;
        String newFlowEditLock = null;
        synchronized (DSSWorkFlowConstant.saveFlowLock.intern(flowID)) {
            version = flowService.saveFlow(flowID, jsonFlow, comment, userName, workspaceName, projectName);
        }
        return Message.ok().data("flowVersion", version).data("flowEditLock", newFlowEditLock);
    }
}


