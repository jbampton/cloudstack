// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.api.query.dao;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.cloud.cpu.CPU;
import com.cloud.dc.ASNumberRangeVO;
import com.cloud.dc.dao.ASNumberRangeDao;
import com.cloud.network.Network;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.element.NsxProviderVO;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ResourceIconResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.api.ApiResponseHelper;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.network.NetworkService;
import com.cloud.resource.icon.ResourceIconVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class DataCenterJoinDaoImpl extends GenericDaoBase<DataCenterJoinVO, Long> implements DataCenterJoinDao {

    private SearchBuilder<DataCenterJoinVO> dofIdSearch;
    @Inject
    public AccountManager _accountMgr;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private NsxProviderDao nsxProviderDao;
    @Inject
    private NetrisProviderDao netrisProviderDao;
    @Inject
    private ASNumberRangeDao asNumberRangeDao;

    protected DataCenterJoinDaoImpl() {

        dofIdSearch = createSearchBuilder();
        dofIdSearch.and("id", dofIdSearch.entity().getId(), SearchCriteria.Op.EQ);
        dofIdSearch.done();

        _count = "select count(distinct id) from data_center_view WHERE ";
    }

    @Override
    public ZoneResponse newMinimalDataCenterResponse(ResponseView view, DataCenterJoinVO dataCenter) {
        ZoneResponse zoneResponse = new ZoneResponse(null);
        zoneResponse.setId(dataCenter.getUuid());
        zoneResponse.setName(dataCenter.getName());
        zoneResponse.setObjectName("zone");
        return zoneResponse;
    }

    @Override
    public ZoneResponse newDataCenterResponse(ResponseView view, DataCenterJoinVO dataCenter, Boolean showCapacities, Boolean showResourceImage) {
        ZoneResponse zoneResponse = new ZoneResponse();
        zoneResponse.setId(dataCenter.getUuid());
        zoneResponse.setName(dataCenter.getName());
        zoneResponse.setSecurityGroupsEnabled(ApiDBUtils.isSecurityGroupEnabledInZone(dataCenter.getId()));
        zoneResponse.setLocalStorageEnabled(dataCenter.isLocalStorageEnabled());
        zoneResponse.setType(ObjectUtils.defaultIfNull(dataCenter.getType(), DataCenter.Type.Core).toString());
        zoneResponse.setStorageAccessGroups(dataCenter.getStorageAccessGroups());

        if ((dataCenter.getDescription() != null) && !dataCenter.getDescription().equalsIgnoreCase("null")) {
            zoneResponse.setDescription(dataCenter.getDescription());
        }

        if (view == ResponseView.Full) {
            zoneResponse.setDns1(dataCenter.getDns1());
            zoneResponse.setDns2(dataCenter.getDns2());
            zoneResponse.setIp6Dns1(dataCenter.getIp6Dns1());
            zoneResponse.setIp6Dns2(dataCenter.getIp6Dns2());
            zoneResponse.setInternalDns1(dataCenter.getInternalDns1());
            zoneResponse.setInternalDns2(dataCenter.getInternalDns2());
            // FIXME zoneResponse.setVlan(dataCenter.get.getVnet());
            zoneResponse.setGuestCidrAddress(dataCenter.getGuestNetworkCidr());

            if (showCapacities != null && showCapacities) {
                zoneResponse.setCapacities(ApiResponseHelper.getDataCenterCapacityResponse(dataCenter.getId()));
            }
        }

        // set network domain info
        zoneResponse.setDomain(dataCenter.getDomain());

        // set domain info

        zoneResponse.setDomainId(dataCenter.getDomainUuid());
        zoneResponse.setDomainName(dataCenter.getDomainName());

        zoneResponse.setNetworkType(dataCenter.getNetworkType().toString());
        zoneResponse.setAllocationState(dataCenter.getAllocationState().toString());
        zoneResponse.setZoneToken(dataCenter.getZoneToken());
        zoneResponse.setDhcpProvider(dataCenter.getDhcpProvider());

        // update tag information
        List<ResourceTagJoinVO> resourceTags = ApiDBUtils.listResourceTagViewByResourceUUID(dataCenter.getUuid(), ResourceObjectType.Zone);
        for (ResourceTagJoinVO resourceTag : resourceTags) {
            ResourceTagResponse tagResponse = ApiDBUtils.newResourceTagResponse(resourceTag, false);
            zoneResponse.addTag(tagResponse);
        }

        if (showResourceImage) {
            ResourceIconVO resourceIcon = ApiDBUtils.getResourceIconByResourceUUID(dataCenter.getUuid(), ResourceObjectType.Zone);
            if (resourceIcon != null) {
                ResourceIconResponse iconResponse = ApiDBUtils.newResourceIconResponse(resourceIcon);
                zoneResponse.setResourceIconResponse(iconResponse);
            }
        }

        setExternalNetworkProviderUsedByZone(zoneResponse, dataCenter.getId());

        List<CPU.CPUArch> clusterArchs = ApiDBUtils.listZoneClustersArchs(dataCenter.getId());
        zoneResponse.setMultiArch(CollectionUtils.isNotEmpty(clusterArchs) && clusterArchs.size() > 1);

        List<ASNumberRangeVO> asNumberRange = asNumberRangeDao.listByZoneId(dataCenter.getId());
        String asRange = asNumberRange.stream().map(range -> range.getStartASNumber() + "-" + range.getEndASNumber()).collect(Collectors.joining(", "));
        zoneResponse.setAsnRange(asRange);

        zoneResponse.setRoutedModeEnabled(RoutedIpv4Manager.RoutedNetworkVpcEnabled.valueIn(dataCenter.getId()));

        zoneResponse.setResourceDetails(ApiDBUtils.getResourceDetails(dataCenter.getId(), ResourceObjectType.Zone));
        zoneResponse.setHasAnnotation(annotationDao.hasAnnotations(dataCenter.getUuid(), AnnotationService.EntityType.ZONE.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));
        zoneResponse.setAllowUserSpecifyVRMtu(NetworkService.AllowUsersToSpecifyVRMtu.valueIn(dataCenter.getId()));
        zoneResponse.setRouterPrivateInterfaceMaxMtu(NetworkService.VRPrivateInterfaceMtu.valueIn(dataCenter.getId()));
        zoneResponse.setRouterPublicInterfaceMaxMtu(NetworkService.VRPublicInterfaceMtu.valueIn(dataCenter.getId()));

        zoneResponse.setObjectName("zone");
        return zoneResponse;
    }

    private void setExternalNetworkProviderUsedByZone(ZoneResponse zoneResponse, Long zoneId) {
        NsxProviderVO nsxProviderVO = nsxProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(nsxProviderVO)) {
            zoneResponse.setNsxEnabled(true);
            zoneResponse.setProvider(Network.Provider.Nsx.getName());
        }

        NetrisProviderVO netrisProviderVO = netrisProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(netrisProviderVO)) {
            zoneResponse.setProvider(Network.Provider.Netris.getName());
        }
    }

    @Override
    public DataCenterJoinVO newDataCenterView(DataCenter dataCenter) {
        SearchCriteria<DataCenterJoinVO> sc = dofIdSearch.create();
        sc.setParameters("id", dataCenter.getId());
        List<DataCenterJoinVO> dcs = searchIncludingRemoved(sc, null, null, false);
        assert dcs != null && dcs.size() == 1 : "No data center found for data center id " + dataCenter.getId();
        return dcs.get(0);
    }

}
