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
package com.cloud.network;

import java.util.Date;
import java.util.List;

import com.cloud.user.User;
import org.apache.cloudstack.api.response.AcquirePodIpCmdResponse;
import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.dc.DataCenter;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.user.Account;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachineProfile;

public interface IpAddressManager {
    String UseSystemPublicIpsCK = "use.system.public.ips";
    ConfigKey<Boolean> UseSystemPublicIps = new ConfigKey<Boolean>("Advanced", Boolean.class, UseSystemPublicIpsCK, "true",
            "If true, when account has dedicated public ip range(s), once the ips dedicated to the account have been consumed ips will be acquired from the system pool",
            true, ConfigKey.Scope.Account);

    ConfigKey<Boolean> RulesContinueOnError = new ConfigKey<Boolean>("Advanced", Boolean.class, "network.rule.delete.ignoreerror", "true",
            "When true, ip address delete (ipassoc) failures are  ignored", true);

    ConfigKey<String> VrouterRedundantTiersPlacement = new ConfigKey<String>(
            String.class,
            "vrouter.redundant.tiers.placement",
            "Advanced",
            "random",
            "Set placement of vrouter ips in redundant mode in vpc tiers, this can be 3 value: `first` to use first ips in tiers, `last` to use last ips in tiers and `random` to take random ips in tiers.",
            true, ConfigKey.Scope.Account, null, null, null, null, null, ConfigKey.Kind.Select, "first,last,random");

    ConfigKey<Boolean> AllowUserListAvailableIpsOnSharedNetwork = new ConfigKey<Boolean>("Advanced", Boolean.class, "allow.user.list.available.ips.on.shared.network", "false",
            "Determines whether users can list available IPs on shared networks",
            true, ConfigKey.Scope.Global);

    /**
     * Assigns a new public ip address.
     *
     * @param dcId
     * @param podId
     * @param owner
     * @param type
     * @param networkId
     * @param requestedIp
     * @param isSystem
     * @return
     * @throws InsufficientAddressCapacityException
     */
    PublicIp assignPublicIpAddress(long dcId, Long podId, Account owner, VlanType type, Long networkId, String requestedIp, boolean isSystem, boolean forSystemVms)
            throws InsufficientAddressCapacityException;

    PublicIp assignSourceNatPublicIpAddress(long dcId, Long podId, Account owner, VlanType type, Long networkId, String requestedIp, boolean isSystem, boolean forSystemVms)
        throws InsufficientAddressCapacityException;

    /**
     * Do all of the work of releasing public ip addresses. Note that if this method fails, there can be side effects.
     *
     * @param userId
     * @param caller
     * @param caller
     * @return true if it did; false if it didn't
     */
    boolean disassociatePublicIpAddress(IpAddress ipAddress, long userId, Account caller);

    boolean applyRules(List<? extends FirewallRule> rules, FirewallRule.Purpose purpose, NetworkRuleApplier applier, boolean continueOnError)
            throws ResourceUnavailableException;

    /**
     * @param userId
     * @param accountId
     * @param zoneId
     * @param vlanId
     * @param guestNetwork
     * @throws InsufficientCapacityException
     * @throws ConcurrentOperationException
     * @throws ResourceUnavailableException
     * @throws ResourceAllocationException
     *             Associates an ip address list to an account. The list of ip addresses are all addresses associated
     *             with the
     *             given vlan id.
     */
    boolean associateIpAddressListToAccount(long userId, long accountId, long zoneId, Long vlanId, Network guestNetwork) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException, ResourceAllocationException;

    boolean applyIpAssociations(Network network, boolean continueOnError) throws ResourceUnavailableException;

    boolean applyIpAssociations(Network network, boolean rulesRevoked, boolean continueOnError, List<? extends PublicIpAddress> publicIps)
            throws ResourceUnavailableException;

    IPAddressVO markIpAsUnavailable(long addrId);

    String acquireGuestIpAddress(Network network, String requestedIp);

    String acquireFirstGuestIpAddress(Network network);

    String acquireLastGuestIpAddress(Network network);

    String acquireGuestIpAddressByPlacement(Network network, String requestedIp);

    boolean applyStaticNats(List<? extends StaticNat> staticNats, boolean continueOnError, boolean forRevoke) throws ResourceUnavailableException;

    IpAddress assignSystemIp(long networkId, Account owner, boolean forElasticLb, boolean forElasticIp) throws InsufficientAddressCapacityException;

    boolean handleSystemIpRelease(IpAddress ip);

    void allocateDirectIp(NicProfile nic, DataCenter dc, VirtualMachineProfile vm, Network network, String requestedIpv4, String requestedIpv6)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException;

    /**
     * @param owner
     * @param guestNetwork
     * @return
     * @throws ConcurrentOperationException
     * @throws InsufficientAddressCapacityException
     */
    PublicIp assignSourceNatIpAddressToGuestNetwork(Account owner, Network guestNetwork) throws InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     *
     * @param ipAddrId
     * @param networkId
     * @param releaseOnFailure
     * @return
     * @throws ResourceAllocationException
     * @throws ResourceUnavailableException
     * @throws InsufficientAddressCapacityException
     * @throws ConcurrentOperationException
     */
    IPAddressVO associateIPToGuestNetwork(long ipAddrId, long networkId, boolean releaseOnFailure) throws ResourceAllocationException, ResourceUnavailableException,
            InsufficientAddressCapacityException, ConcurrentOperationException;

    IpAddress allocatePortableIp(Account ipOwner, Account caller, long dcId, Long networkId, Long vpcID) throws ConcurrentOperationException,
            ResourceAllocationException, InsufficientAddressCapacityException;

    boolean releasePortableIpAddress(long addrId);

    IPAddressVO associatePortableIPToGuestNetwork(long ipAddrId, long networkId, boolean releaseOnFailure) throws ResourceAllocationException,
            ResourceUnavailableException, InsufficientAddressCapacityException, ConcurrentOperationException;

    IPAddressVO disassociatePortableIPToGuestNetwork(long ipAddrId, long networkId) throws ResourceAllocationException, ResourceUnavailableException,
            InsufficientAddressCapacityException, ConcurrentOperationException;

    boolean isPortableIpTransferableFromNetwork(long ipAddrId, long networkId);

    void transferPortableIP(long ipAddrId, long currentNetworkId, long newNetworkId) throws ResourceAllocationException, ResourceUnavailableException,
            InsufficientAddressCapacityException, ConcurrentOperationException;;

    /**
     * @param addr
     */
    void markPublicIpAsAllocated(IPAddressVO addr);

    /**
     * @param owner
     * @param guestNtwkId
     * @param vpcId
     * @param dcId
     * @param isSourceNat
     * @return
     * @throws ConcurrentOperationException
     * @throws InsufficientAddressCapacityException
     */
    PublicIp assignDedicateIpAddress(Account owner, Long guestNtwkId, Long vpcId, long dcId, boolean isSourceNat)
            throws ConcurrentOperationException, InsufficientAddressCapacityException;

    IpAddress allocateIp(Account ipOwner, boolean isSystem, Account caller, User callerId, DataCenter zone, Boolean displayIp, String ipaddress)
            throws ConcurrentOperationException, ResourceAllocationException, InsufficientAddressCapacityException;

    PublicIp assignPublicIpAddressFromVlans(long dcId, Long podId, Account owner, VlanType type, List<Long> vlanDbIds, Long networkId, String requestedIp, String requestedGateway, boolean isSystem)
            throws InsufficientAddressCapacityException;

    PublicIp getAvailablePublicIpAddressFromVlans(long dcId, Long podId, Account owner, VlanType type, List<Long> vlanDbIds, Long networkId, String requestedIp, boolean isSystem)
            throws InsufficientAddressCapacityException;

    @DB
    void allocateNicValues(NicProfile nic, DataCenter dc, VirtualMachineProfile vm, Network network, String requestedIpv4, String requestedIpv6)
            throws InsufficientVirtualNetworkCapacityException, InsufficientAddressCapacityException;

    int getRuleCountForIp(Long addressId, FirewallRule.Purpose purpose, FirewallRule.State state);

    String allocateGuestIP(Network network, String requestedIp) throws InsufficientAddressCapacityException;

    String allocatePublicIpForGuestNic(Network network, Long podId, Account ipOwner, String requestedIp) throws InsufficientAddressCapacityException;

    AcquirePodIpCmdResponse allocatePodIp(String zoneId, String podId) throws ConcurrentOperationException, ResourceAllocationException;

    public boolean isIpEqualsGatewayOrNetworkOfferingsEmpty(Network network, String requestedIp);

    void releasePodIp(Long id) throws CloudRuntimeException;

    boolean isUsageHidden(IPAddressVO address);

    List<IPAddressVO> listAvailablePublicIps(final long dcId,
                                             final Long podId,
                                             final List<Long> vlanDbIds,
                                             final Account owner,
                                             final VlanType vlanUse,
                                             final Long guestNetworkId,
                                             final boolean sourceNat,
                                             final boolean assign,
                                             final boolean allocate,
                                             final String requestedIp,
                                             final String requestedGateway,
                                             final boolean isSystem,
                                             final Long vpcId,
                                             final Boolean displayIp,
                                             final boolean forSystemVms,
                                             final boolean lockOneRow)
            throws InsufficientAddressCapacityException;

    public static final String MESSAGE_ASSIGN_IPADDR_EVENT = "Message.AssignIpAddr.Event";
    public static final String MESSAGE_RELEASE_IPADDR_EVENT = "Message.ReleaseIpAddr.Event";


    /**
     * Checks if the given public IP address is not in active quarantine.
     * It returns `true` if:
     *  <ul>
     *   <li>The IP was never in quarantine;</li>
     *   <li>The IP was in quarantine, but the quarantine expired;</li>
     *   <li>The IP is still in quarantine; however, the new owner is the same as the previous owner, therefore, the IP can be allocated.</li>
     * </ul>
     *
     * It returns `false` if:
     * <ul>
     *   <li>The IP is in active quarantine and the new owner is different from the previous owner.</li>
     * </ul>
     *
     * @param ip used to check if it is in active quarantine.
     * @param account used to identify the new owner of the public IP.
     * @return true if the IP can be allocated, and false otherwise.
     */
    boolean canPublicIpAddressBeAllocated(IpAddress ip, Account account);

    /**
     * Adds the given public IP address to quarantine for the duration of the global configuration `public.ip.address.quarantine.duration` value.
     *
     * @param publicIpAddress to be quarantined.
     * @param domainId used to retrieve the quarantine duration.
     * @return the {@link PublicIpQuarantine} persisted in the database.
     */
    PublicIpQuarantine addPublicIpAddressToQuarantine(IpAddress publicIpAddress, Long domainId);

    /**
     * Prematurely removes a public IP address from quarantine. It is required to provide a reason for removing it.
     *
     * @param quarantineProcessId the ID of the active quarantine process.
     * @param removalReason       for prematurely removing the public IP address from quarantine.
     */
    void removePublicIpAddressFromQuarantine(Long quarantineProcessId, String removalReason);

    /**
     * Updates the end date of a public IP address in active quarantine. It can increase and decrease the duration of the quarantine.
     *
     * @param quarantineProcessId the ID of the quarantine process.
     * @param endDate             the new end date for the quarantine.
     * @return the updated quarantine object.
     */
    PublicIpQuarantine updatePublicIpAddressInQuarantine(Long quarantineProcessId, Date endDate);

    void updateSourceNatIpAddress(IPAddressVO requestedIp, List<IPAddressVO> userIps) throws Exception;
}
