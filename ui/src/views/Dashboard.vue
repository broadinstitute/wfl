<template>
  <v-container class="dashboard">
    <v-container fluid fill-width>
      <h1 id="main-header">Welcome to Zero Server!</h1>
    </v-container>

    <v-container fluid fill-width>
      <v-row>
        <v-col key="1">
          <v-card class="mx-auto" outlined>
            <v-card-title>System Information</v-card-title>
            <v-card-subtitle>The build info about the system</v-card-subtitle>
            <v-card-text>
              System Build Hash: {{ versions.zero}}
              <v-divider></v-divider>
              System Build Time: {{ versions.time}}
              <v-divider></v-divider>
              System Version: {{ versions.version}}
              <v-divider></v-divider>
              System User: {{ versions.user}}
              <v-divider></v-divider>
            </v-card-text>
            <v-card-actions>
              <v-btn text>Learn More</v-btn>
            </v-card-actions>
          </v-card>
          <v-responsive width="50%"></v-responsive>
        </v-col>

        <v-col key="2">
          <v-card class="mx-auto" outlined>
            <v-card-title>Pipelines Information</v-card-title>
            <v-card-subtitle>The version info about the supported pipelines</v-card-subtitle>
            <v-card-text>
              ExternalExomeReprocessing: {{ versions["ExternalExomeReprocessing"]}}
              <v-divider></v-divider>
              dsde-pipelines: {{ versions["dsde-pipelines"]}}
              <v-divider></v-divider>
              WhiteAlbumExomeReprocessing: {{ versions["WhiteAlbumExomeReprocessing"]}}
              <v-divider></v-divider>
              Module00a: {{ versions["Module00a"]}}
              <v-divider></v-divider>
            </v-card-text>
            <v-card-actions>
              <v-btn text>Learn More</v-btn>
            </v-card-actions>
          </v-card>
          <v-responsive width="50%"></v-responsive>
        </v-col>
      </v-row>
    </v-container>
  </v-container>
</template>

<script>
import axios from "axios";

export default {
  name: "Dashboard",
  components: {},
  data() {
    return {
      versions: {
        note: "Fetching information..."
      }
    };
  },
  methods: {
    getVersions() {
      axios.get("/version").then(response => {
        if (response.status == 200) {
          this.versions = response.data;
        }
      });
    }
  },

  created: function() {
    this.getVersions();
  }
};
</script>

<style scoped>
#main-header {
  text-align: center;
}
</style>