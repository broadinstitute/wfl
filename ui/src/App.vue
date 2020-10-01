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
    const store = this.$store
    window.gapi.load('auth2', initAuth);
    function initAuth() {
      axios.get("/oauth2id").then(response => {
        if (response.status == 200) {
          window.gapi.auth2.init({
            client_id: response.data["oauth2-client-id"]
          }).then(()=> {
            window.gapi.auth2.getAuthInstance().currentUser.listen((user) => {
              if(user.isSignedIn()) {
                store.dispatch('auth/login', user)
              }
            })
          });
        } else {
          alert("Unable to prepare login, WFL backend may be offline!")
        }
      });
    }
    axios.interceptors.response.use(null, (error) => {
      const unauthorized = (error.response && error.response.status === 401)
      if(unauthorized && error.config) {
        if(this.$store.getters['sidebar/getSideBar'] === true) {
          this.$store.dispatch('sidebar/toggleSideBar')
        }
        this.$router.push('/error')
      }
      return Promise.reject(error);
    });
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
