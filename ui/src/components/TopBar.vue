<template>
  <div id="topbar">
    <v-app-bar app color="#74AE43" dark :clipped-left="$vuetify.breakpoint.lgAndUp">
      <v-container fluid>
        <v-row justify="space-around" align="center">
          <v-col cols="5">
            <v-toolbar-title>
              <v-app-bar-nav-icon v-if="isAuthenticated" class="mr-2" @click.stop="toggleSideBar"></v-app-bar-nav-icon>

              <v-btn class="mr-2" icon>
                <v-avatar>
                  <v-img
                    src="../assets/logo.png"
                    alt="hornet"
                    class="rotating-logo"
                    :contain="true"
                  ></v-img>
                </v-avatar>
              </v-btn>

              <span>{{ title }}</span>
            </v-toolbar-title>
          </v-col>

          <v-spacer></v-spacer>

          <v-col cols="5">
            <v-row justify="end" align="center">
            <v-btn class="mr-2"  text >Status: {{ status.status }}</v-btn>
            <v-btn class="mr-2"  outlined v-if="isAuthenticated" v-bind:to="swagger_link">Swagger API</v-btn>
            <v-btn outlined v-if="isAuthenticated" v-on:click="logout">Logout</v-btn>
            </v-row>
          </v-col>
        </v-row>
      </v-container>
    </v-app-bar>
  </div>
</template>

<script>
import axios from "axios";
import { mapActions } from "vuex";

export default {
  name: "TopBar",
  props: {
    title: {
      type: String
    }
  },
  data() {
    return {
      status: { status: "N/A" },
      swagger_link: "/swagger"
    };
  },
  computed: {
    isAuthenticated: function() {
      return this.$store.getters['auth/authenticated'];
    }
  },
  methods: {
    logout() {
      window.gapi.auth2.getAuthInstance().signOut().then(user => {
        this.$store.dispatch('auth/logout', user).then(() => {
          if(this.$store.getters['sidebar/getSideBar'] === true) {
            this.toggleSideBar()
          }
          this.$router.push('/login')
        });
      });
    },
    getStatus() {
      axios.get("/status").then(response => (this.status = response.data));
    },
    ...mapActions("sidebar", ["toggleSideBar"])
  },

  created: function() {
    this.getStatus();
  }
};
</script>

<style scoped>
.rotating-logo {
  animation: rotating infinite 3s linear;
  height: 40vmin;
  pointer-events: none;
}

@keyframes rotating {
  from {
    transform: rotate(360deg);
  }
  to {
    transform: rotate(0deg);
  }
}

.aa {
  min-width: 10px;
  padding-left: 16px;
}
</style>