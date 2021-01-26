<template>
  <div class="workload">
    <h1>Workloads managed by WFL</h1>
    <v-row>
      <v-col v-for="workload in workloads" :key="workload.uuid" fluid fill-width>
        <v-card class="mx-auto" outlined>
          <v-card-title>Workload {{ workload.uuid }} </v-card-title>
          <div v-for='(item, key, index) in workload' v-bind:key="index" class="card-text-wrapper">
            <v-card-text v-if='key != "workflows"'>
              <b> {{ key }} </b>: {{ item }}
              <v-divider></v-divider>
            </v-card-text>
          </div>
          <div class="text-center">
            <v-progress-circular
              :size="100"
              :width="15"
              :value="calculateWorkflowsProgress(workload.workflows)"
              color="green">
              {{ calculateWorkflowsProgress(workload.workflows) }}
            </v-progress-circular>
          </div>
        </v-card>
      </v-col>
    </v-row>
  </div>
</template>

<script>
import axios from "axios";

export default {
  name: "Workload",
  data() {
    return {
      workloads: [],
    };
  },
  methods: {
    getAndFetchWorkloads() {
      // this is just a hard-coded proof of concept function
      axios.get("/api/v1/workload").then((response) => {
        if (
          response.status == 200 &&
          response.headers["content-type"] === "application/json"
        ) {
          this.workloads = response.data;
        }
      });
    },
    calculateWorkflowsProgress(workflows) {
      const succeeded = workflows.filter(function(i){
        if ( i.status === "Succeeded" ) {
          return true;
        } else {
          return false;
        }
      })
      return succeeded.length / workflows.length * 100
    }
  },
  mounted: function () {
    this.getAndFetchWorkloads();
  },
};
</script>

<style scoped>
#data-table {
  text-align: center;
}

.card-text-wrapper .v-card__text {
  padding-bottom: 1px;
}

.v-progress-circular {
  margin: 1rem;
}
</style>
