<template>
  <div class="workload">
    <h1>Current status counts in supported environments</h1>
    <InfoTable id="data-table" v-bind:headers="status_counts_headers" v-bind:items="status_counts" />
  </div>
</template>

<script>
import InfoTable from "../components/InfoTable.vue";
import axios from "axios";

export default {
  name: "Workload",
  components: {
    InfoTable
  },
  data() {
    return {
      status_counts_headers: [
        { text: "Name", sortable: true, value: "name" },
        { text: "Aborted", sortable: true, value: "Aborted" },
        { text: "Failed", sortable: true, value: "Failed" },
        { text: "On Hold", sortable: true, value: "OnHold" },
        { text: "Running", sortable: true, value: "Running" },
        {
          text: "Submitted",
          sortable: true,
          value: "Submitted"
        },
        {
          text: "Succeeded",
          sortable: true,
          value: "Succeeded"
        },
        { text: "total", sortable: true, value: "total" }
      ],
      status_counts: []
    };
  },
  methods: {
    getSomeStatusCounts() {
      // this is just a hard-coded proof of concept function
      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "gotc-dev"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "GOTC-DEV",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });

      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "gotc-prod"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "GOTC-PROD",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });

      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "pharma5"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "PHARMA5",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });

      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "jg-prod"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "JG-PROD",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });

      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "gotc-staging"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "GOTC-STAGING",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });

      axios
        .get("/api/v1/statuscounts", {
          params: {
            environment: "xx"
          }
        })
        .then(response => {
          if (response.status == 200) {
            this.status_counts.push({
              name: "EXTERNAL-EXOMES",
              Aborted: response.data["Aborted"],
              Aborting: response.data["Aborting"],
              Failed: response.data["Failed"],
              OnHold: response.data["On Hold"],
              Running: response.data["Running"],
              Submitted: response.data["Submitted"],
              Succeeded: response.data["Succeeded"],
              total: response.data["total"]
            });
          }
        });
    }
  },

  mounted: function() {
    this.getSomeStatusCounts();
  }
};
</script>

<style scoped>
#data-table {
  text-align: center;
}
</style>