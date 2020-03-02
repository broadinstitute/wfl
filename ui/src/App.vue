<template>
  <v-app id="app">
    <SideBar v-bind:modules="modules" />

    <TopBar v-bind:title="'Zero Server'" />

    <v-content>
      <v-container fluid fill-width>
        <router-view>
          <!-- route outlet -->
          <!-- component matched by the route will render here -->
        </router-view>
      </v-container>
    </v-content>

    <v-footer color="#74AE43" app :inset="false">
      <span class="white--text">&copy; Broad Institute, Hornet Team 2020</span>
    </v-footer>
  </v-app>
</template>

<script>
import TopBar from "./components/TopBar.vue";
import SideBar from "./components/SideBar.vue";
import axios from "axios";

export default {
  name: "app",
  components: {
    TopBar,
    SideBar
  },
  data() {
    return {
      modules: [{ text: "loading..." }]
    };
  },
  methods: {
    getEnvironments() {
      axios.get("/api/v1/environments").then(response => {
        if (
          response.status == 200 &&
          response.headers["content-type"] === "application/json"
        ) {
          this.environments = response.data;

          // for the modules
          let tempData = Object.keys(response.data);
          let tempModules = [];
          tempData.forEach(module => {
            tempModules.push({ text: module });
          });

          this.modules = tempModules;
        }
      });
    }
  },

  created: function() {
    this.getEnvironments();
  },

  mounted: function() {}
};
</script>

<style>
#app {
  font-family: "Avenir", Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #2c3e50;
  margin-top: 60px;
}

#main-header,
#data-table {
  text-align: center;
}
</style>
