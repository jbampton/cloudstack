# -- coding: utf-8 --
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
from . import CsHelper
import logging


class CsRoute:

    """ Manage routes """

    def __init__(self):
        self.table_prefix = "Table_"

    def get_tablename(self, name):
        return self.table_prefix + name

    def add_table(self, devicename):
        tablenumber = 100 + int(devicename[3:])
        tablename = self.get_tablename(devicename)
        str = "%s %s" % (tablenumber, tablename)
        filename = "/etc/iproute2/rt_tables"
        logging.info("Adding route table: " + str + " to " + filename + " if not present ")
        if not CsHelper.definedinfile(filename, str):
            CsHelper.execute("sudo echo " + str + " >> /etc/iproute2/rt_tables")
        # remove "from all table tablename" if exists, else it will interfer with
        # routing of unintended traffic
        if self.findRule("from all lookup " + tablename):
            CsHelper.execute("sudo ip rule delete from all table " + tablename)

    def flush_table(self, tablename):
        CsHelper.execute("ip route flush table %s" % (tablename))
        CsHelper.execute("ip route flush cache")

    def add_route(self, dev, address):
        """ Wrapper method that adds table name and device to route statement """
        # ip route add dev eth1 table Table_eth1 10.0.2.0/24
        table = self.get_tablename(dev)

        if not table or not address:
            empty_param = "table" if not table else "address"
            logging.info("Empty parameter received %s while trying to add route, skipping" % empty_param)
        else:
            logging.info("Adding route: dev " + dev + " table: " +
                         table + " network: " + address + " if not present")
            cmd = "default via %s table %s proto static" % (address, table)
            self.set_route(cmd)

    def add_network_route(self, dev, address):
        """ Wrapper method that adds table name and device to route statement """
        # ip route add dev eth1 table Table_eth1 10.0.2.0/24
        table = self.get_tablename(dev)

        if not table or not address:
            empty_param = "table" if not table else "address"
            logging.info("Empty parameter received %s while trying to add network route, skipping" % empty_param)
        else:
            logging.info("Adding route: dev " + dev + " table: " +
                         table + " network: " + address + " if not present")
            cmd = "throw %s table %s proto static" % (address, table)
            self.set_route(cmd)

    def set_route(self, cmd, method="add"):
        """ Add a route if it is not already defined """
        found = False
        search = cmd
        if "throw" in search:
            search = search.replace("throw", "")
        for i in CsHelper.execute("ip route show " + search):
            found = True
        if not found and method == "add":
            logging.info("Add " + cmd)
            cmd = "ip route add " + cmd
        elif not found and method == "change":
            logging.info("Change " + cmd)
            cmd = "ip route change " + cmd
        elif found and method == "delete":
            logging.info("Delete " + cmd)
            cmd = "ip route delete " + cmd
        else:
            return
        CsHelper.execute(cmd)

    def add_defaultroute(self, gateway):
        """  Add a default route
        :param str gateway
        :return: bool
        """
        if not gateway:
            raise Exception("Gateway cannot be None.")

        if self.defaultroute_exists():
            return False
        else:
            cmd = "default via " + gateway
            logging.info("Adding default route")
            self.set_route(cmd)
            return True

    def defaultroute_exists(self):
        """ Return True if a default route is present
        :return: bool
        """
        logging.info("Checking if default ipv4 route is present")
        route_found = CsHelper.execute("ip -4 route list 0/0")

        if len(route_found) > 0:
            logging.info("Default route found: " + route_found[0])
            return True
        else:
            logging.warn("No default route found!")
            return False

    def add_or_change_defaultroute(self, gateway):
        """  Add a default route if not found, or change the default route if found
        :param str gateway
        :return: bool
        """
        if not gateway:
            raise Exception("Gateway cannot be None.")

        cmd = "default via " + gateway
        if self.defaultroute_exists():
            logging.info("Changing default route")
            self.set_route(cmd, method="change")
            return True
        else:
            logging.info("Adding default route")
            self.set_route(cmd)
            return True

    def findRule(self, rule):
        for i in CsHelper.execute("ip rule show"):
            if rule in i.strip():
                return True
        return False

    def set_route_v6(self, cmd, method="add"):
        """ Add a IPv6 route if it is not already defined """
        found = False
        search = cmd
        for i in CsHelper.execute("ip -6 route show " + search):
            found = True
        if not found and method == "add":
            logging.info("Add " + cmd)
            cmd = "ip -6 route add " + cmd
        elif found and method == "delete":
            logging.info("Delete " + cmd)
            cmd = "ip -6 route delete " + cmd
        else:
            return
        CsHelper.execute(cmd)

    def add_defaultroute_v6(self, gateway):
        """  Add a default route
        # for example, ip -6 route add default via fd80:20:20:20::1
        :param str gateway
        :return: bool
        """
        if not gateway:
            raise Exception("Gateway cannot be None.")

        logging.info("Checking if default ipv6 route is present")
        route_found = CsHelper.execute("ip -6 route list default")
        if len(route_found) > 0:
            logging.info("Default IPv6 route found: " + route_found[0])
            return False
        else:
            cmd = "default via " + gateway
            logging.info("Adding default IPv6 route")
            self.set_route_v6(cmd)
            return True

    def add_network_route_v6(self, dev, address):
        """ Wrapper method that adds table name and device to route statement """
        # ip -6 route add dev eth1 2021:10:10:10::1/64
        logging.info("Adding IPv6  route: dev " + dev + " network: " + address + " if not present")
        cmd = "%s dev %s proto kernel" % (address, dev)
        self.set_route_v6(cmd)

    def delete_network_route_v6(self, dev, address):
        """ Wrapper method that deletes table name and device from route statement """
        # ip -6 route del dev eth1 2021:10:10:10::1/64
        logging.info("Deleting IPv6 route: dev " + dev + " network: " + address + " if present")
        cmd = "%s dev %s" % (address, dev)
        self.set_route_v6(cmd, method="delete")
