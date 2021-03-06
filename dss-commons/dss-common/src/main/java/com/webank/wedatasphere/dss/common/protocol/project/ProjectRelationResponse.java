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

package com.webank.wedatasphere.dss.common.protocol.project;


import com.webank.wedatasphere.dss.common.label.DSSLabel;

import java.util.List;

public class ProjectRelationResponse {

    private Long dssProjectId;

    private String appConnName;

    private List<DSSLabel> dssLabels;

    private Long appInstanceProjectId;

    public ProjectRelationResponse(Long dssProjectId, String appConnName, List<DSSLabel> dssLabels, Long appInstanceProjectId) {
        this.dssProjectId = dssProjectId;
        this.appConnName = appConnName;
        this.dssLabels = dssLabels;
        this.appInstanceProjectId = appInstanceProjectId;
    }

    public Long getDssProjectId() {
        return dssProjectId;
    }

    public void setDssProjectId(Long dssProjectId) {
        this.dssProjectId = dssProjectId;
    }

    public String getAppConnName() {
        return appConnName;
    }

    public void setAppConnName(String appConnName) {
        this.appConnName = appConnName;
    }

    public List<DSSLabel> getDssLabels() {
        return dssLabels;
    }

    public void setDssLabels(List<DSSLabel> dssLabels) {
        this.dssLabels = dssLabels;
    }

    public Long getAppInstanceProjectId() {
        return appInstanceProjectId;
    }

    public void setAppInstanceProjectId(Long appInstanceProjectId) {
        this.appInstanceProjectId = appInstanceProjectId;
    }
}
