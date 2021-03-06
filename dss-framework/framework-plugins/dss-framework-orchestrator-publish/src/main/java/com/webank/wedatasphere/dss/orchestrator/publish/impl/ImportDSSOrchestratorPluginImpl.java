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

package com.webank.wedatasphere.dss.orchestrator.publish.impl;

import static com.webank.wedatasphere.dss.orchestrator.publish.impl.ExportDSSOrchestratorPluginImpl.DEFAULT_ORC_NAME;

import com.webank.wedatasphere.dss.appconn.core.AppConn;
import com.webank.wedatasphere.dss.common.entity.IOEnv;
import com.webank.wedatasphere.dss.common.exception.DSSErrorException;
import com.webank.wedatasphere.dss.common.label.DSSLabel;
import com.webank.wedatasphere.dss.common.utils.DSSExceptionUtils;
import com.webank.wedatasphere.dss.common.utils.IoUtils;
import com.webank.wedatasphere.dss.common.utils.ZipHelper;
import com.webank.wedatasphere.dss.contextservice.service.ContextService;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorInfo;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorVersion;
import com.webank.wedatasphere.dss.orchestrator.common.protocol.RequestProjectImportOrchestrator;
import com.webank.wedatasphere.dss.orchestrator.core.DSSOrchestrator;
import com.webank.wedatasphere.dss.orchestrator.core.plugin.AbstractDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.common.ref.OrchestratorImportRequestRef;
import com.webank.wedatasphere.dss.orchestrator.core.service.BMLService;
import com.webank.wedatasphere.dss.orchestrator.core.utils.OrchestratorUtils;
import com.webank.wedatasphere.dss.orchestrator.db.dao.OrchestratorMapper;
import com.webank.wedatasphere.dss.orchestrator.loader.OrchestratorManager;
import com.webank.wedatasphere.dss.orchestrator.loader.utils.OrchestratorLoaderUtils;
import com.webank.wedatasphere.dss.orchestrator.publish.ImportDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.publish.io.input.MetaInputService;
import com.webank.wedatasphere.dss.sender.service.DSSSenderServiceFactory;
import com.webank.wedatasphere.dss.standard.app.development.ref.CommonResponseRef;
import com.webank.wedatasphere.dss.standard.app.development.service.RefImportService;
import com.webank.wedatasphere.dss.standard.app.development.standard.DevelopmentIntegrationStandard;
import com.webank.wedatasphere.dss.standard.app.sso.Workspace;
import com.webank.wedatasphere.dss.standard.common.desc.AppInstance;
import com.webank.wedatasphere.dss.standard.common.entity.ref.RefFactory;
import java.io.File;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javafx.util.Pair;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class ImportDSSOrchestratorPluginImpl extends AbstractDSSOrchestratorPlugin implements ImportDSSOrchestratorPlugin {

    @Autowired
    private MetaInputService metaInputService;
    @Autowired
    private OrchestratorMapper orchestratorMapper;
    @Autowired
    private BMLService bmlService;
    @Autowired
    private ContextService contextService;
    @Autowired
    private OrchestratorManager orchestratorManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long importOrchestrator(String userName, String workspaceName, String projectName, Long projectId, String resourceId, String version,
        List<DSSLabel> dssLabels, Workspace workspace) throws Exception {
        Long appId = null;
        //1?????????BML???Orchestrator????????????
        String inputZipPath = IoUtils.generateIOPath(userName, projectName, DEFAULT_ORC_NAME + ".zip");
        bmlService.downloadToLocalPath(userName, resourceId, version, inputZipPath);
        String inputPath = ZipHelper.unzip(inputZipPath);

        IOEnv ioEnv = IoUtils.readIOEnv(inputPath);
        //2?????????Info??????(??????????????????)
        List<DSSOrchestratorInfo> dssOrchestratorInfos = metaInputService.importOrchestrator(inputPath);
        DSSOrchestratorInfo importDssOrchestratorInfo = dssOrchestratorInfos.get(0);

        //????????????ID?????????????????????
        String uuid = orchestratorMapper.getOrcNameByParam(importDssOrchestratorInfo.getProjectId(),importDssOrchestratorInfo.getName());
        //??????UUID???????????????????????????
        DSSOrchestratorInfo existFlag = orchestratorMapper.getOrchestratorByUUID(importDssOrchestratorInfo.getUUID());
        //add ???update??????????????????????????????id????????????????????????????????????
        //todo ???????????????????????????ID??????????????????????????????????????????????????????ID???????????????ID,??????????????????????????????????????????????????????????????????
        if (null != existFlag) {
            //?????????????????????????????????????????????
            if (StringUtils.isNotBlank(uuid) && !uuid.equals(importDssOrchestratorInfo.getUUID())) {
                DSSExceptionUtils
                        .dealErrorException(61002, "The same orchestration name already exists" ,DSSErrorException.class);

            }
            //??????Orchestrator?????????????????????????????????????????????????????????????????????name,????????????????????????
            orchestratorMapper.updateOrchestrator(importDssOrchestratorInfo);
        } else {
            //?????????????????????????????????????????????
            if (StringUtils.isNotBlank(uuid)) {
                DSSExceptionUtils
                        .dealErrorException(61002, "The same orchestration name already exists" ,DSSErrorException.class);
            }
            orchestratorMapper.addOrchestrator(importDssOrchestratorInfo);
        }
        String flowZipPath = inputPath + File.separator + "orc_flow.zip";
        //3??????????????????zip??????bml
        InputStream inputStream = bmlService.readLocalResourceFile(userName, flowZipPath);
        Map<String, Object> resultMap = bmlService.upload(userName, inputStream, importDssOrchestratorInfo.getName() + "_orc_flow.zip", projectName);
        String orcResourceId = resultMap.get("resourceId").toString();
        String orcBmlVersion = resultMap.get("version").toString();


        //4???????????????Version??????
        DSSOrchestratorVersion dssOrchestratorVersion = new DSSOrchestratorVersion();
        dssOrchestratorVersion.setAppId(null);
        dssOrchestratorVersion.setComment("orchestrator import");
        dssOrchestratorVersion.setOrchestratorId(importDssOrchestratorInfo.getId());
        dssOrchestratorVersion.setContent("");
        dssOrchestratorVersion.setProjectId(projectId);
        dssOrchestratorVersion.setSource("Orchestrator create");
        dssOrchestratorVersion.setUpdater(userName);

        String oldVersion = orchestratorMapper.getLatestVersion(importDssOrchestratorInfo.getId());
        if (StringUtils.isNotEmpty(oldVersion)){
            dssOrchestratorVersion.setVersion(OrchestratorUtils.increaseVersion(oldVersion));
        }else{
            dssOrchestratorVersion.setVersion(OrchestratorUtils.generateNewVersion());
        }

        //5??????????????????ContextId
        String contextId = contextService.createContextID(workspaceName, projectName, importDssOrchestratorInfo.getName(), dssOrchestratorVersion.getVersion(), userName);
        dssOrchestratorVersion.setFormatContextId(contextId);
        LOGGER.info("Create a new ContextId for import: {} ", contextId);

        dssOrchestratorVersion.setUpdateTime(new Date());
        orchestratorMapper.addOrchestratorVersion(dssOrchestratorVersion);

        //6????????????????????????????????????????????????Visualis???Qualities
        DSSOrchestrator dssOrchestrator = orchestratorManager.getOrCreateOrchestrator(userName,
            workspaceName, importDssOrchestratorInfo.getType(), importDssOrchestratorInfo.getAppConnName(), dssLabels);
        AppConn orchestratorAppConn = dssOrchestrator.getAppConn();
        Pair<AppInstance, DevelopmentIntegrationStandard> standardPair = OrchestratorLoaderUtils
            .getOrcDevelopStandard(userName, workspaceName, importDssOrchestratorInfo, dssLabels);
        if (null != standardPair) {
            RefImportService refImportService = standardPair.getValue().getRefImportService(standardPair.getKey());
            OrchestratorImportRequestRef orchestratorImportRequestRef = null;
            try {
                orchestratorImportRequestRef =
                    (OrchestratorImportRequestRef) RefFactory.INSTANCE.newRef(OrchestratorImportRequestRef.class,
                        orchestratorAppConn.getClass().getClassLoader(), "com.webank.wedatasphere.dss.appconn.workflow.ref");
            } catch (final Exception e) {
                DSSExceptionUtils
                    .dealErrorException(61001, "failed to new ref with class " + OrchestratorImportRequestRef.class.getName(), e, DSSErrorException.class);
            }
            orchestratorImportRequestRef.setResourceId(orcResourceId);
            orchestratorImportRequestRef.setBmlVersion(orcBmlVersion);
            orchestratorImportRequestRef.setProjectId(projectId);
            orchestratorImportRequestRef.setUserName(userName);
            orchestratorImportRequestRef.setWorkspaceName(workspaceName);
            orchestratorImportRequestRef.setWorkspace(workspace);
            orchestratorImportRequestRef.setProjectName(projectName);
            orchestratorImportRequestRef.setContextID(contextId);
            //todo getSourceEnv
            orchestratorImportRequestRef.setSourceEnv(ioEnv);
            orchestratorImportRequestRef.setOrcVersion(dssOrchestratorVersion.getVersion());
            CommonResponseRef responseRef = (CommonResponseRef) refImportService.getRefImportOperation().importRef(orchestratorImportRequestRef);
            appId = responseRef.getOrcId();
            String appContent = responseRef.getContent();

            //??????????????????
            dssOrchestratorVersion.setAppId(appId);
            dssOrchestratorVersion.setContent(appContent);
            orchestratorMapper.updateOrchestratorVersion(dssOrchestratorVersion);

        }

        RequestProjectImportOrchestrator projectImportOrchestrator = new RequestProjectImportOrchestrator();
        BeanUtils.copyProperties(importDssOrchestratorInfo,projectImportOrchestrator);
        projectImportOrchestrator.setVersionId(dssOrchestratorVersion.getId());
        //?????????????????????????????????
        DSSSenderServiceFactory.getOrCreateServiceInstance().getProjectServerSender()
                .ask(projectImportOrchestrator);
        return dssOrchestratorVersion.getOrchestratorId();
    }
}
