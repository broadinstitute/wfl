<template>
  <div class="modules">
    <h1>Supported modules and their settings</h1>
    <InfoPanel title="Environments" v-bind:infodata="environments" />
  </div>
</template>

<script>
import InfoPanel from "../components/InfoPanel.vue";
import axios from "axios";

export default {
  name: "modules",
  components: {
    InfoPanel
  },
  data() {
    return {
      environments: {
        note: "You might need to login to see the information here!"
      }
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
        }
      });
    }
  },

  mounted: function() {
    this.getEnvironments();
  }
};
</script>
