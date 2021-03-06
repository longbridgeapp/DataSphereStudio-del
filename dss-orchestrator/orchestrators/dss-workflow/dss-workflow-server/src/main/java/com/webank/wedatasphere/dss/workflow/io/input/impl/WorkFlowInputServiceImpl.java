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

package com.webank.wedatasphere.dss.workflow.io.input.impl;

import com.webank.wedatasphere.dss.common.entity.IOEnv;
import com.webank.wedatasphere.dss.common.entity.Resource;
import com.webank.wedatasphere.dss.common.exception.DSSErrorException;
import com.webank.wedatasphere.dss.contextservice.service.ContextService;
import com.webank.wedatasphere.dss.contextservice.service.impl.ContextServiceImpl;
import com.webank.wedatasphere.dss.standard.app.sso.Workspace;
import com.webank.wedatasphere.dss.workflow.common.entity.DSSFlow;
import com.webank.wedatasphere.dss.workflow.common.entity.DSSFlowRelation;
import com.webank.wedatasphere.dss.workflow.common.parser.NodeParser;
import com.webank.wedatasphere.dss.workflow.common.parser.WorkFlowParser;
import com.webank.wedatasphere.dss.workflow.dao.FlowMapper;
import com.webank.wedatasphere.dss.workflow.io.input.InputRelationService;
import com.webank.wedatasphere.dss.workflow.io.input.NodeInputService;
import com.webank.wedatasphere.dss.workflow.io.input.WorkFlowInputService;
import com.webank.wedatasphere.dss.workflow.service.BMLService;
import org.apache.linkis.server.BDPJettyServerHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class WorkFlowInputServiceImpl implements WorkFlowInputService {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private BMLService bmlService;

    @Autowired
    private WorkFlowParser workFlowParser;

    @Autowired
    private NodeInputService nodeInputService;

    @Autowired
    private NodeParser nodeParser;

    @Autowired
    private InputRelationService inputRelationService;

    @Autowired
    private FlowMapper flowMapper;
    private static ContextService contextService = ContextServiceImpl.getInstance();

    @Override
    public void inputWorkFlow(String userName,
                              String workspaceName,
                              DSSFlow dssFlow,
                              String version,
                              String projectName,
                              String inputProjectPath,
                              Long parentFlowId,
                              Workspace workspace,
                              String orcVersion,
                              String contextId) throws DSSErrorException, IOException {
        //todo ???????????????????????????????????????,?????????????????????
        String flowInputPath = inputProjectPath + File.separator + dssFlow.getName();
        String flowJsonPath = flowInputPath + File.separator + dssFlow.getName() + ".json";
        String creator = dssFlow.getCreator();
        String flowJson = bmlService.readLocalFlowJsonFile(userName, flowJsonPath);
        //????????????subflow,??????????????????subflow??????????????????parrentflow???json??????
        // TODO: 2020/7/31 ??????update???????????????saveContent
        String updateFlowJson = updateFlowContextId(flowJson, contextId);
        updateFlowJson = inputWorkFlowNodes(userName, projectName, updateFlowJson, dssFlow, flowInputPath, workspace, orcVersion);
        List<? extends DSSFlow> subFlows = dssFlow.getChildren();
        if (subFlows != null) {
            for (DSSFlow subFlow : subFlows) {
                inputWorkFlow(userName, workspaceName, subFlow, version, projectName, inputProjectPath, dssFlow.getId(), workspace, orcVersion,contextId);
            }
        }

        updateFlowJson = uploadFlowResourceToBml(userName, updateFlowJson, flowInputPath, projectName);

        DSSFlow updateDssFlow = uploadFlowJsonToBml(userName, projectName, dssFlow, updateFlowJson);
        //todo add dssflow to database
        contextService.checkAndSaveContext(updateFlowJson, String.valueOf(parentFlowId));
        flowMapper.updateFlowInputInfo(updateDssFlow);


    }

    private String updateFlowContextId(String flowJson, String contextId) throws IOException {

//        String contextID = contextService.checkAndInitContext(flowJson, parentFlowIdStr, workspace, projectName, flowName, flowVersion, userName);
        String updatedFlowJson = workFlowParser.updateFlowJsonWithKey(flowJson, "contextID", contextId);
        return updatedFlowJson;
    }

    private String inputWorkFlowNodes(String userName, String projectName, String flowJson, DSSFlow dssFlow, String flowPath, Workspace workspace, String orcVersion) throws DSSErrorException, IOException {
        List<String> nodeJsonList = workFlowParser.getWorkFlowNodesJson(flowJson);
        if (nodeJsonList == null) {
            throw new DSSErrorException(90073, "????????????????????????????????????????????????" + dssFlow.getName());
        }
        String updateContextId = workFlowParser.getValueWithKey(flowJson, "contextID");
        if (nodeJsonList.size() == 0) {
            return flowJson;
        }
        List<DSSFlow> subflows = (List<DSSFlow>) dssFlow.getChildren();
        String workFlowResourceSavePath = flowPath + File.separator + "resource" + File.separator;
        String appConnResourceSavePath = flowPath + File.separator + "appconn-resource";
        List<Map<String, Object>> nodeJsonListRes = new ArrayList<>();
        if (nodeJsonList.size() > 0) {
            for (String nodeJson : nodeJsonList) {
                // TODO: 2020/3/20 ???????????????appconn??????
                String updateNodeJson = nodeInputService.uploadResourceToBml(userName, nodeJson, workFlowResourceSavePath, projectName);
                updateNodeJson = nodeInputService.uploadAppConnResource(userName, projectName,
                        dssFlow, updateNodeJson, updateContextId, appConnResourceSavePath, workspace, orcVersion);

                Map<String, Object> nodeJsonMap = BDPJettyServerHelper.jacksonJson().readValue(updateNodeJson, Map.class);
                //??????subflowID
                String nodeType = nodeJsonMap.get("jobType").toString();
                if ("workflow.subflow".equals(nodeType)) {
                    String subFlowName = nodeJsonMap.get("title").toString();
                    List<DSSFlow> DSSFlowList = subflows.stream().filter(subflow ->
                            subflow.getName().equals(subFlowName)
                    ).collect(Collectors.toList());
                    if (DSSFlowList.size() == 1) {
                        updateNodeJson = nodeInputService.updateNodeSubflowID(updateNodeJson, DSSFlowList.get(0).getId());
                        nodeJsonMap = BDPJettyServerHelper.jacksonJson().readValue(updateNodeJson, Map.class);
                        nodeJsonListRes.add(nodeJsonMap);
                    } else if (DSSFlowList.size() > 1) {
                        logger.error("???????????????????????????????????????????????????????????????" + subFlowName);
                        throw new DSSErrorException(90077, "???????????????????????????????????????????????????????????????" + subFlowName);
                    } else {
                        logger.error("???????????????????????????????????????????????????????????????" + subFlowName);
                        throw new DSSErrorException(90078, "??????????????????????????????????????????????????????" + subFlowName);
                    }
                } else {
                    nodeJsonListRes.add(nodeJsonMap);
                }
            }
        }

        return workFlowParser.updateFlowJsonWithKey(flowJson, "nodes", nodeJsonListRes);

    }

    private String uploadFlowResourceToBml(String userName, String flowJson, String flowResourcePath, String projectName) throws IOException {

        List<Resource> resourceList = workFlowParser.getWorkFlowResources(flowJson);
        //??????????????????resourceId???version save??????????????????
        if (resourceList != null) {
            resourceList.forEach(resource -> {
                InputStream resourceInputStream = readFlowResource(userName, resource, flowResourcePath);
                Map<String, Object> bmlReturnMap = bmlService.upload(userName, resourceInputStream, UUID.randomUUID().toString() + ".json", projectName);
                resource.setResourceId(bmlReturnMap.get("resourceId").toString());
                resource.setVersion(bmlReturnMap.get("version").toString());
            });
            if (resourceList.size() == 0) {
                return flowJson;
            }
        }
        return workFlowParser.updateFlowJsonWithKey(flowJson, "resources", resourceList);
    }

    private InputStream readFlowResource(String userName, Resource resource, String flowResourcePath) {
        // TODO: 2020/3/20 ???????????????,????????????resouce ????????????,????????????
        String readPath = flowResourcePath + File.separator + "resource" + File.separator + resource.getResourceId() + ".re";
        return bmlService.readLocalResourceFile(userName, readPath);
    }


    public DSSFlow uploadFlowJsonToBml(String userName, String projectName, DSSFlow dssFlow, String flowJson) {
        //??????rsourceId?????????jsonPath
        Long flowID = dssFlow.getId();
        String resourceId = dssFlow.getResourceId();
        //??????????????????resourceId???version save??????????????????
        Map<String, Object> bmlReturnMap;
//        if (resourceId != null) {
//            bmlReturnMap = bmlService.update(userName, resourceId, flowJson);
//        } else {
            //??????????????????resourceId???version save??????????????????
            bmlReturnMap = bmlService.upload(userName, flowJson, UUID.randomUUID().toString() + ".json", projectName);
//        }

        dssFlow.setCreator(userName);
        dssFlow.setBmlVersion(bmlReturnMap.get("version").toString());
        dssFlow.setResourceId(bmlReturnMap.get("resourceId").toString());
        dssFlow.setDescription("import update workflow");
        dssFlow.setSource("????????????");

        //version??????????????????
        return dssFlow;
    }

    /**
     * @param projectId ????????????projectID
     * @param userName
     * @param dssFlows
     * @param sourceEnv
     * @return
     */
    @Override
    public List<DSSFlow> persistenceFlow(Long projectId, String userName, List<DSSFlow> dssFlows,
                                         List<DSSFlowRelation> dssFlowRelations, IOEnv sourceEnv) {
        List<DSSFlow> rootFlows = dssFlows.stream().filter(DSSFlow::getRootFlow).collect(Collectors.toList());
        return rootFlows.stream().map(rf -> setSubFlow(rf, dssFlows, dssFlowRelations, sourceEnv, projectId, userName, null))
                .collect(Collectors.toList());
    }

    public DSSFlow setSubFlow(DSSFlow dssFlow, List<DSSFlow> dssFlows,
                              List<DSSFlowRelation> dssFlowRelations,
                              IOEnv sourceEnv,
                              Long projectId,
                              String username, DSSFlow parentFlow) {
        DSSFlow cyFlow = new DSSFlow();
        BeanUtils.copyProperties(dssFlow, cyFlow, "children", "flowVersions");
        //??????flow??????
        cyFlow.setProjectID(projectId);
        cyFlow.setCreator(username);
        cyFlow.setCreateTime(new Date());
        cyFlow.setId(null);
        flowMapper.insertFlow(cyFlow);
        //??????input ????????????

        //inputRelationService.insertFlowInputRelation(dssFlow.getId(), sourceEnv, cyFlow.getId());

        //??????????????????relation???
        if (parentFlow != null) {
            persistenceFlowRelation(cyFlow.getId(), parentFlow.getId());
            if (parentFlow.getChildren() == null) {
                parentFlow.setChildren(new ArrayList<DSSFlow>());
            }
            parentFlow.addChildren(cyFlow);
        }
        List<Long> subFlowIds = dssFlowRelations.stream().filter(r -> r.getParentFlowID().equals(dssFlow.getId())).map(DSSFlowRelation::getFlowID).collect(Collectors.toList());
        for (Long subFlowId : subFlowIds) {
            DSSFlow subDSSFlow = dssFlows.stream().filter(f -> subFlowId.equals(f.getId())).findFirst().orElse(null);
            if (dssFlow.getChildren() == null) {
                dssFlow.setChildren(new ArrayList<DSSFlow>());
            }
            dssFlow.addChildren(subDSSFlow);
            setSubFlow(subDSSFlow, dssFlows, dssFlowRelations, sourceEnv, projectId, username, cyFlow);
        }
        return cyFlow;
    }

    private void persistenceFlowRelation(Long flowID, Long parentFlowID) {
        DSSFlowRelation relation = flowMapper.selectFlowRelation(flowID, parentFlowID);
        if (relation == null) {
            flowMapper.insertFlowRelation(flowID, parentFlowID);
        }
    }
}
