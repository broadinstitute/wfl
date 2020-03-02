<template>
  <v-navigation-drawer
    :clipped="$vuetify.breakpoint.lgAndUp"
    app
    id="sidebar"
    v-model="showSideBar"
  >
    <v-list>
      <router-link to="/" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.dashboard.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.dashboard.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>

      <router-link to="/query" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.query.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.query.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>

      <router-link to="/workload" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.workload.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.workload.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>

      <router-link to="/modules" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.modules.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.modules.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>

      <router-link to="/settings" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.settings.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.settings.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>

      <router-link to="/help" v-slot="{ navigate }">
        <v-list-item @click="navigate">
          <v-list-item-action>
            <v-icon>{{ drawer_items.help.icon }}</v-icon>
          </v-list-item-action>
          <v-list-item-content>
            <v-list-item-title>{{ drawer_items.help.text }}</v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </router-link>
    </v-list>
  </v-navigation-drawer>
</template>

<script>
import { mapGetters } from "vuex";

export default {
  name: "SideBar",
  props: {
    modules: Array
  },
  computed: {
    // TODO: resolve warnings:
    // Computed property "computedCurrentshowSideBar" was assigned to but it has no setter.
    ...mapGetters("sidebar", {
      showSideBar: "getSideBar"
    }),

    drawer_items() {
      return {
        dashboard: { icon: "dashboard", text: "Dashboard" },
        query: { icon: "query_builder", text: "Query" },
        workload: { icon: "traffic", text: "Workload" },
        modules: { icon: "extension", text: "Modules" },
        // TODO: Below is reserverd here since we wanna modules to be collapseable later
        // modules: {
        //   icon: "keyboard_arrow_up",
        //   "icon-alt": "extension",
        //   text: "Supported Modules",
        //   model: false,
        //   children: this.modules
        // },
        settings: { icon: "settings", text: "Settings" },
        help: { icon: "help", text: "Help" }
      };
    }
  }
};
</script>