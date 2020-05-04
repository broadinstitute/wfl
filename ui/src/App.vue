<template>
  <v-app id="app">
    <SideBar v-bind:modules="modules" />

    <TopBar v-bind:title="'WorkFlow Launcher'" />

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
import axios from "axios";
import TopBar from "./components/TopBar.vue";
import SideBar from "./components/SideBar.vue";

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
  created: function() {
    window.gapi.load('auth2', initAuth);
    function initAuth() {
      window.gapi.auth2.init({
        client_id: '450819267403-n17keaafi8u1udtopauapv0ntjklmgrs.apps.googleusercontent.com'
      });
    }
    axios.defaults.headers.common.authentication = `Bearer ${this.$store.getters['auth/authToken']}`
  }
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
