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

import { shallowRef, defineAsyncComponent } from 'vue'
import store from '@/store'

export default {
  name: 'host',
  title: 'label.hosts',
  icon: 'database-outlined',
  docHelp: 'conceptsandterminology/concepts.html#about-hosts',
  permission: ['listHostsMetrics'],
  searchFilters: ['name', 'zoneid', 'podid', 'clusterid', 'arch', 'hypervisor'],
  resourceType: 'Host',
  filters: () => {
    const filters = ['enabled', 'disabled', 'maintenance', 'up', 'down', 'disconnected', 'alert']
    return filters
  },
  params: { type: 'routing' },
  columns: () => {
    const fields = [
      'name', 'state', 'resourcestate', 'ipaddress', 'arch', 'hypervisor',
      { field: 'systeminstances', customTitle: 'system.vms' }, 'version'
    ]
    const metricsFields = ['instances', 'powerstate', 'cpunumber', 'cputotalghz', 'cpuusedghz', 'cpuallocatedghz', 'memorytotalgb', 'memoryusedgb', 'memoryallocatedgb', 'networkread', 'networkwrite']
    if (store.getters.metrics) {
      fields.push(...metricsFields)
    }
    fields.push('clustername')
    fields.push('zonename')
    fields.push('managementservername')
    return fields
  },
  details: ['name', 'id', 'resourcestate', 'ipaddress', 'hypervisor', 'externalprovisioner', 'arch', 'type', 'clustername', 'podname', 'zonename', 'storageaccessgroups', 'clusterstorageaccessgroups', 'podstorageaccessgroups', 'zonestorageaccessgroups', 'managementservername', 'disconnected', 'created', 'externaldetails'],
  tabs: [{
    name: 'details',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/DetailsTab.vue')))
  }, {
    name: 'events',
    resourceType: 'Host',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/EventsTab.vue'))),
    show: () => { return 'listEvents' in store.getters.apis }
  }, {
    name: 'comments',
    component: shallowRef(defineAsyncComponent(() => import('@/components/view/AnnotationsTab.vue')))
  }],
  related: [{
    name: 'vm',
    title: 'label.instances',
    param: 'hostid'
  }],
  actions: [
    {
      api: 'addHost',
      icon: 'plus-outlined',
      label: 'label.add.host',
      docHelp: 'installguide/configuration.html#adding-a-host',
      listView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/HostAdd.vue')))
    },
    {
      api: 'updateHost',
      icon: 'edit-outlined',
      label: 'label.edit',
      dataView: true,
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/HostUpdate')))
    },
    {
      api: 'updateHostPassword',
      icon: 'key-outlined',
      label: 'label.action.change.password',
      dataView: true,
      popup: true,
      show: (record) => { return record.hypervisor !== 'External' },
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/ChangeHostPassword.vue')))
    },
    {
      api: 'provisionCertificate',
      icon: 'safety-certificate-outlined',
      label: 'label.action.secure.host',
      message: 'message.action.secure.host',
      dataView: true,
      show: (record) => {
        return record.hypervisor === 'KVM' || record.hypervisor === store.getters.customHypervisorName
      },
      args: ['hostid'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'reconnectHost',
      icon: 'forward-outlined',
      label: 'label.action.force.reconnect',
      message: 'message.confirm.action.force.reconnect',
      dataView: true,
      show: (record) => { return ['Disconnected', 'Up', 'Alert'].includes(record.state) }
    },
    {
      api: 'updateHost',
      icon: 'pause-circle-outlined',
      label: 'label.disable.host',
      message: 'message.confirm.disable.host',
      dataView: true,
      show: (record) => record.resourcestate === 'Enabled',
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/HostEnableDisable'))),
      events: {
        'refresh-data': () => {
          store.dispatch('refreshCurrentPage')
        }
      }
    },
    {
      api: 'updateHost',
      icon: 'play-circle-outlined',
      label: 'label.enable.host',
      message: 'message.confirm.enable.host',
      dataView: true,
      show: (record) => record.resourcestate === 'Disabled',
      popup: true,
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/HostEnableDisable'))),
      events: {
        'refresh-data': () => {
          store.dispatch('refreshCurrentPage')
        }
      }
    },
    {
      api: 'prepareHostForMaintenance',
      icon: 'plus-square-outlined',
      label: 'label.action.enable.maintenance.mode',
      message: 'message.action.host.enable.maintenance.mode',
      docHelp: 'adminguide/hosts.html#maintaining-hypervisors-on-hosts',
      dataView: true,
      show: (record) => { return record.resourcestate === 'Enabled' }
    },
    {
      api: 'cancelHostMaintenance',
      icon: 'minus-square-outlined',
      label: 'label.action.cancel.maintenance.mode',
      message: 'message.action.cancel.maintenance.mode',
      docHelp: 'adminguide/hosts.html#maintaining-hypervisors-on-hosts',
      dataView: true,
      show: (record) => { return record.resourcestate === 'Maintenance' || record.resourcestate === 'ErrorInMaintenance' || record.resourcestate === 'PrepareForMaintenance' || record.resourcestate === 'ErrorInPrepareForMaintenance' }
    },
    {
      api: 'configureOutOfBandManagement',
      icon: 'setting-outlined',
      label: 'label.outofbandmanagement.configure',
      message: 'label.outofbandmanagement.configure',
      docHelp: 'adminguide/hosts.html#out-of-band-management',
      dataView: true,
      popup: true,
      show: (record) => { return record.hypervisor !== 'External' },
      component: shallowRef(defineAsyncComponent(() => import('@/views/infra/ConfigureHostOOBM')))
    },
    {
      api: 'enableOutOfBandManagementForHost',
      icon: 'plus-circle-outlined',
      label: 'label.outofbandmanagement.enable',
      message: 'label.outofbandmanagement.enable',
      docHelp: 'adminguide/hosts.html#out-of-band-management',
      dataView: true,
      show: (record) => {
        return record.hypervisor !== 'External' && !(record?.outofbandmanagement?.enabled === true)
      },
      args: ['hostid'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'disableOutOfBandManagementForHost',
      icon: 'minus-circle-outlined',
      label: 'label.outofbandmanagement.disable',
      message: 'label.outofbandmanagement.disable',
      docHelp: 'adminguide/hosts.html#out-of-band-management',
      dataView: true,
      show: (record) => {
        return record.hypervisor !== 'External' && record?.outofbandmanagement?.enabled === true
      },
      args: ['hostid'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'issueOutOfBandManagementPowerAction',
      icon: 'login-outlined',
      label: 'label.outofbandmanagement.action.issue',
      message: 'label.outofbandmanagement.action.issue',
      docHelp: 'adminguide/hosts.html#out-of-band-management',
      dataView: true,
      show: (record) => {
        return record.hypervisor !== 'External' && record?.outofbandmanagement?.enabled === true
      },
      args: ['hostid', 'action'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        },
        action: {
          options: ['ON', 'OFF', 'CYCLE', 'RESET', 'SOFT', 'STATUS']
        }
      }
    },
    {
      api: 'changeOutOfBandManagementPassword',
      icon: 'key-outlined',
      label: 'label.outofbandmanagement.changepassword',
      message: 'label.outofbandmanagement.changepassword',
      docHelp: 'adminguide/hosts.html#out-of-band-management',
      dataView: true,
      show: (record) => {
        return record.hypervisor !== 'External' && record?.outofbandmanagement?.enabled === true
      },
      args: ['hostid', 'password'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'configureHAForHost',
      icon: 'tool-outlined',
      label: 'label.ha.configure',
      message: 'label.ha.configure',
      docHelp: 'adminguide/reliability.html#ha-for-hosts',
      dataView: true,
      show: (record) => { return ['KVM', 'Simulator'].includes(record.hypervisor) },
      args: ['hostid', 'provider'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        },
        provider: {
          options: ['KVMHAProvider']
        }
      }
    },
    {
      api: 'enableHAForHost',
      icon: 'eye-outlined',
      label: 'label.ha.enable',
      message: 'label.ha.enable',
      docHelp: 'adminguide/reliability.html#ha-for-hosts',
      dataView: true,
      show: (record) => {
        return record.hypervisor !== 'External' && !(record?.hostha?.haenable === true)
      },
      args: ['hostid'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'disableHAForHost',
      icon: 'eye-invisible-outlined',
      label: 'label.ha.disable',
      message: 'label.ha.disable',
      docHelp: 'adminguide/reliability.html#ha-for-hosts',
      dataView: true,
      show: (record) => {
        return record.hostha && record.hostha.haenable &&
        record.hostha.haenable === true
      },
      args: ['hostid'],
      mapping: {
        hostid: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'startRollingMaintenance',
      icon: 'control-outlined',
      label: 'label.start.rolling.maintenance',
      message: 'label.start.rolling.maintenance',
      docHelp: 'adminguide/hosts.html#kvm-rolling-maintenance',
      dataView: true,
      show: (record) => {
        return record.hypervisor === 'KVM' && (record.resourcestate === 'Enabled' || record.resourcestate === 'ErrorInMaintenance')
      },
      args: ['timeout', 'payload', 'forced', 'hostids'],
      mapping: {
        hostids: {
          value: (record) => { return record.id }
        }
      }
    },
    {
      api: 'declareHostAsDegraded',
      icon: 'exception-outlined',
      label: 'label.declare.host.as.degraded',
      message: 'label.declare.host.as.degraded',
      dataView: true,
      show: (record) => {
        return record.resourcestate !== 'Degraded' && (record.state === 'Alert' || record.state === 'Disconnected')
      }
    },
    {
      api: 'cancelHostAsDegraded',
      icon: 'file-done-outlined',
      label: 'label.cancel.host.as.degraded',
      message: 'label.cancel.host.as.degraded',
      dataView: true,
      show: (record) => {
        return record.resourcestate === 'Degraded'
      }
    },
    {
      api: 'deleteHost',
      icon: 'delete-outlined',
      label: 'label.action.remove.host',
      docHelp: 'adminguide/hosts.html#removing-hosts',
      dataView: true,
      args: ['forced'],
      show: (record) => { return ['Maintenance', 'Disabled', 'Down', 'Alert', 'Disconnected'].includes(record.resourcestate) }
    }
  ]
}
