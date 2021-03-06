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

import static com.webank.wedatasphere.dss.common.utils.ZipHelper.zipExportProject;

import com.webank.wedatasphere.dss.common.entity.IOType;
import com.webank.wedatasphere.dss.common.exception.DSSErrorException;
import com.webank.wedatasphere.dss.common.label.DSSLabel;
import com.webank.wedatasphere.dss.common.utils.DSSExceptionUtils;
import com.webank.wedatasphere.dss.common.utils.IoUtils;
import com.webank.wedatasphere.dss.contextservice.service.ContextService;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorInfo;
import com.webank.wedatasphere.dss.orchestrator.common.entity.DSSOrchestratorVersion;
import com.webank.wedatasphere.dss.orchestrator.core.exception.DSSOrchestratorErrorException;
import com.webank.wedatasphere.dss.orchestrator.core.plugin.AbstractDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.common.ref.OrchestratorCopyRequestRef;
import com.webank.wedatasphere.dss.orchestrator.common.ref.OrchestratorCopyResponseRef;
import com.webank.wedatasphere.dss.orchestrator.common.ref.OrchestratorExportRequestRef;
import com.webank.wedatasphere.dss.orchestrator.core.service.BMLService;
import com.webank.wedatasphere.dss.orchestrator.core.utils.OrchestratorUtils;
import com.webank.wedatasphere.dss.orchestrator.db.dao.OrchestratorMapper;
import com.webank.wedatasphere.dss.orchestrator.loader.utils.OrchestratorLoaderUtils;
import com.webank.wedatasphere.dss.orchestrator.publish.ExportDSSOrchestratorPlugin;
import com.webank.wedatasphere.dss.orchestrator.publish.io.export.MetaExportService;
import com.webank.wedatasphere.dss.standard.app.development.service.RefCRUDService;
import com.webank.wedatasphere.dss.standard.app.development.service.RefExportService;
import com.webank.wedatasphere.dss.standard.app.development.standard.DevelopmentIntegrationStandard;
import com.webank.wedatasphere.dss.standard.app.sso.Workspace;
import com.webank.wedatasphere.dss.standard.common.desc.AppInstance;
import com.webank.wedatasphere.dss.standard.common.entity.ref.AbstractResponseRef;
import com.webank.wedatasphere.dss.standard.common.entity.ref.AppConnRefFactoryUtils;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Date;
import java.util.List;
import java.util.Map;
import javafx.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;


@Component
public class ExportDSSOrchestratorPluginImpl extends AbstractDSSOrchestratorPlugin implements ExportDSSOrchestratorPlugin {

    static final String DEFAULT_ORC_NAME = "default_orc";

    @Autowired
    private BMLService bmlService;
    @Autowired
    private OrchestratorMapper orchestratorMapper;
    @Autowired
    private MetaExportService metaExportService;
    @Autowired
    private ContextService contextService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> exportOrchestrator(String userName, String workspaceName, Long orchestratorId, Long orcVersionId, String projectName,
        List<DSSLabel> dssLabels, boolean addOrcVersion, Workspace workspace) throws DSSErrorException {
        //1?????????info??????
        if (orcVersionId == null || orcVersionId < 0){
            LOGGER.info("orchestratorVersionId is {}", orcVersionId);
            //????????????????????????orcId??????????????????versionId
            orcVersionId = orchestratorMapper.findLatestOrcVersionId(orchestratorId);
        }
        DSSOrchestratorVersion dssOrchestratorVersion = orchestratorMapper.getOrchestratorVersion(orcVersionId);
        DSSOrchestratorInfo dssOrchestratorInfo = orchestratorMapper.getOrchestrator(dssOrchestratorVersion.getOrchestratorId());
        if (dssOrchestratorInfo != null) {
            String orcExportSaveBasePath = IoUtils.generateIOPath(userName, DEFAULT_ORC_NAME, "");
            try {
                Files.createDirectories(Paths.get(orcExportSaveBasePath).toAbsolutePath().normalize());
                //?????????????????????project??????
                IoUtils.generateIOType(IOType.ORCHESTRATOR, orcExportSaveBasePath);
                //????????????????????????env
                IoUtils.generateIOEnv(orcExportSaveBasePath);
                metaExportService.export(dssOrchestratorInfo, orcExportSaveBasePath);
            } catch (IOException e) {
                LOGGER.error("Failed to export metaInfo in orchestrator server for orc({}) in version {}.", orchestratorId, orcVersionId, e);
                DSSExceptionUtils.dealErrorException(60099, "Failed to export metaInfo in orchestrator server.", e, DSSOrchestratorErrorException.class);
            }
            LOGGER.info("{} ????????????Orchestrator: {} ??????ID???: {} ", userName, dssOrchestratorInfo.getName(), orcVersionId);

            //2????????????????????????????????????????????????Visualis???Qualities
            Pair<AppInstance, DevelopmentIntegrationStandard> standardPair = OrchestratorLoaderUtils
                .getOrcDevelopStandard(userName, workspaceName, dssOrchestratorInfo, dssLabels);
            if (null != standardPair) {
                RefExportService refExportService = standardPair.getValue().getRefExportService(standardPair.getKey());

                if (refExportService == null) {
                    LOGGER.error("for {} to export orchestrator {} refExportService is null", userName, dssOrchestratorInfo.getName());
                    DSSExceptionUtils.dealErrorException(60089, "refExportService is null", DSSErrorException.class);
                }
                OrchestratorExportRequestRef orchestratorExportRequestRef =
                    AppConnRefFactoryUtils.newAppConnRef(OrchestratorExportRequestRef.class,
                        refExportService.getClass().getClassLoader(), dssOrchestratorInfo.getType());
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("orchestrator Export Request Ref class is {}", orchestratorExportRequestRef.getClass());
                }
                orchestratorExportRequestRef.setAppId(dssOrchestratorVersion.getAppId());
                orchestratorExportRequestRef.setProjectId(dssOrchestratorVersion.getProjectId());
                orchestratorExportRequestRef.setProjectName(projectName);
                orchestratorExportRequestRef.setUserName(userName);
                orchestratorExportRequestRef.setWorkspace(workspace);
                orchestratorExportRequestRef.setDSSLabels(dssLabels);
                AbstractResponseRef responseRef = (AbstractResponseRef) refExportService.getRefExportOperation().
                    exportRef(orchestratorExportRequestRef);
                String resourceId = responseRef.getValue("resourceId").toString();
                String version = responseRef.getValue("version").toString();
                bmlService.downloadToLocalPath(userName, resourceId, version, orcExportSaveBasePath + "orc_flow.zip");
            }

            //??????????????????
            String exportPath = zipExportProject(orcExportSaveBasePath);

            //3???????????????zip?????????BML
            InputStream inputStream = bmlService.readLocalResourceFile(userName, exportPath);
            Map<String, Object> resultMap = bmlService.upload(userName, inputStream,
                dssOrchestratorInfo.getName() + ".OrcExport", projectName);

            //4??????????????????????????????Orc?????????
            if (addOrcVersion) {
                Long orcIncreaseVersionId = orchestratorVersionIncrease(dssOrchestratorInfo.getId(),
                    userName, dssOrchestratorInfo.getComment(),
                    workspaceName, dssOrchestratorInfo, projectName, dssLabels);
                resultMap.put("orcVersionId", orcIncreaseVersionId);
            } else {
                resultMap.put("orcVersionId", orcVersionId);
            }
            return resultMap;
            //4?????????BML????????????
        } else {
            throw new DSSErrorException(90038, "???Orchestrator??????????????????????????????????????????????????????");
        }
    }

    @Override
    public Long orchestratorVersionIncrease(Long orcId,
        String userName,
        String comment,
        String workspaceName,
        DSSOrchestratorInfo dssOrchestratorInfo,
        String projectName,
        List<DSSLabel> dssLabels) throws DSSErrorException {
        //??????????????????,json????????? subflowID
        DSSOrchestratorVersion dssOrchestratorVersion = orchestratorMapper.getLatestOrchestratorVersionById(orcId);

        // TODO: 2020/3/25 set updator(userID ?????????userName???)
        dssOrchestratorVersion.setUpdateTime(new Date());
        Long oldOrcVersionId = dssOrchestratorVersion.getId();
        String oldOrcVersion = dssOrchestratorVersion.getVersion();
        dssOrchestratorVersion.setId(null);
        // ?????????project?????? ,version??????01,????????????????????????,version + 1
        dssOrchestratorVersion.setVersion(OrchestratorUtils.increaseVersion(oldOrcVersion));

        //?????????comment???????????????????????????,????????????????????????comment???????????????release from..
        dssOrchestratorVersion.setComment(String.format("release from version %s", oldOrcVersion));
        //??????????????????comment
        DSSOrchestratorVersion updateCommentVersion = new DSSOrchestratorVersion();
        updateCommentVersion.setId(oldOrcVersionId);
        String realComment = comment != null ? comment : "release comment";
        updateCommentVersion.setComment(realComment);
        orchestratorMapper.updateOrchestratorVersion(updateCommentVersion);

        //??????AppConn???????????????????????????????????????app??????????????????????????????????????????????????????????????????????????????

        Pair<AppInstance,DevelopmentIntegrationStandard> standardPair = OrchestratorLoaderUtils.getOrcDevelopStandard(userName, workspaceName, dssOrchestratorInfo, dssLabels);
        if(standardPair == null){
            LOGGER.error("dev Process Service is null");
            throw new DSSOrchestratorErrorException(61105, "dev Process Service is null");
        }
        RefCRUDService refcrudservice =standardPair.getValue().getRefCRUDService (standardPair.getKey());
        if (null != refcrudservice) {
            try {
                OrchestratorCopyRequestRef orchestratorCopyRequestRef =
                    AppConnRefFactoryUtils.newAppConnRef(OrchestratorCopyRequestRef.class,
                        refcrudservice.getClass().getClassLoader(), dssOrchestratorInfo.getAppConnName());
                orchestratorCopyRequestRef.setCopyOrcAppId(dssOrchestratorVersion.getAppId());
                orchestratorCopyRequestRef.setCopyOrcVersionId(dssOrchestratorVersion.getOrchestratorId());
                orchestratorCopyRequestRef.setUserName(userName);
                orchestratorCopyRequestRef.setProjectName(projectName);

                //5??????????????????ContextId
                String contextId = contextService.createContextID(workspaceName, projectName, dssOrchestratorInfo.getName(), dssOrchestratorVersion.getVersion(), userName);
                dssOrchestratorVersion.setContextId(contextId);
                LOGGER.info("Create a new ContextId for increase: {} ", contextId);
                orchestratorCopyRequestRef.setContextID(contextId);
                OrchestratorCopyResponseRef orchestratorCopyResponseRef =
                    (OrchestratorCopyResponseRef) refcrudservice.getRefCopyOperation().copyRef(orchestratorCopyRequestRef);
                dssOrchestratorVersion.setAppId(orchestratorCopyResponseRef.getCopyTargetAppId());
                dssOrchestratorVersion.setContent(orchestratorCopyResponseRef.getCopyTargetContent());
                //update appconn node contextId
                dssOrchestratorVersion.setFormatContextId(contextId);
                orchestratorMapper.addOrchestratorVersion(dssOrchestratorVersion);
            } catch (final Exception t) {
                LOGGER.error("Failed to copy new orcVersion in orchestrator server for orc({}).", orcId, t);
                DSSExceptionUtils.dealErrorException(60099, "Failed to copy app in orchestrator server", t, DSSOrchestratorErrorException.class);
            }
            return dssOrchestratorVersion.getId();
        } else {
            throw new DSSOrchestratorErrorException(10023, "????????????????????????Ref????????????????????????????????????");
        }
    }
}
