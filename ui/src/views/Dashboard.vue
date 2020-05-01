<template>
  <v-container class="dashboard">
    <v-container fluid fill-width>
      <h1 id="main-header">Welcome to WorkFlow Launcher Server!</h1>
    </v-container>

    <v-container fluid fill-width>
      <v-row>
        <v-col key="1">
          <v-card class="mx-auto" outlined>
            <v-card-title>System Information</v-card-title>
            <v-card-subtitle>The build info about the system</v-card-subtitle>
            <v-card-text>
              <b> System Build Hash </b>: {{ versions.version.commit}}
              <v-divider></v-divider>
              <b> System Commit Time </b>: {{ versions.version.committed}}
              <v-divider></v-divider>
              <b> System Build Time </b>: {{ versions.version.built}}
              <v-divider></v-divider>
              <b> System Version </b>: {{ versions.version.version}}
              <v-divider></v-divider>
              <b> Latest Built by </b>: {{ versions.version.user}}
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
              <div v-for='(item, key, index) in versions["pipeline-versions"]' v-bind:key="index">
                <b> {{ key }} </b>: {{ item }}
                <v-divider></v-divider>
              </div>
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