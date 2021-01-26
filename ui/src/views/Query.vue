<template>
  <v-container class="query">
    <v-container fluid fill-width>
      <h1>Query workload</h1>
    </v-container>

    <v-container fluid fill-width>
      <v-form ref="form" v-model="valid" lazy-validation @submit.prevent="validateAndPost">
        <v-row>
          <v-col cols="12" sm="6">
            <p> Please choose only one query field </p>
            <v-text-field
              v-model="workloadUUID"
              :rules="[rules.workloadUUID]"
              label="Workload UUID"
              clearable
            ></v-text-field>

            <v-text-field
              v-model="projectName"
              label="Project Name"
              clearable
            ></v-text-field>

            <v-container>
              <v-btn :disabled="!valid" color="success" class="mr-4" @click="validateAndPost">Query</v-btn>

              <v-btn color="error" class="mr-4" @click="reset">Reset Form</v-btn>
            </v-container>
          </v-col>
        </v-row>
      </v-form>
    </v-container>

    <v-container fluid fill-width>
      <v-card outlined :loading="queryResultsIsLoading">
        <v-card-title>
          Total Results: {{ this.totalResults }}
        </v-card-title>

        <v-card-text>
          <VueJsonPretty
            v-bind:data="showResults"
            v-bind:showLength="true"
            v-bind:showLine="true"
            v-bind:highlightMouseoverNode="true"
            v-bind:deep="2"
          ></VueJsonPretty>
        </v-card-text>
      </v-card>
    </v-container>
  </v-container>
</template>

<script>
import VueJsonPretty from "vue-json-pretty";
import axios from "axios";

export default {
  name: "Query",
  components: {
    VueJsonPretty
  },
  data() {
    return {
      queryResultsIsLoading: true,
      workloadUUID: null,
      projectName: null,
      showResults: {
        results: "You might need to login to query for workload!"
      },
      valid: true,
      rules: {
        workloadUUID: v => {
          const pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-5][0-9a-f]{3}-[089ab][0-9a-f]{3}-[0-9a-f]{12}$/i
          if (v) {
            return pattern.test(v) || 'Invalid workload UUID.'
          } else {
            // don't validate if no value is provided
            return true
          }
        }
      }
    };
  },
  watch: {
    showResults: function () {
      if (this.showResults) {
        this.queryResultsIsLoading = false
      }

    }
  },
  computed: {
    totalResults() {
      if (this.showResults) {
        return this.showResults.length
      } else {
        return "N/A"
      }
    }
  },
  methods: {
    validateAndPost() {
      //validate and query
      if (this.$refs.form.validate()) {
        this.snackbar = true;

        this.showResults.results = "Fetching querying results..."

        axios
          .get("/api/v1/workload", {
            params: {
              uuid: this.workloadUUID,
              project: this.projectName
            }
          })
          .then(response => {
            if (
              response.status == 200 &&
              response.headers["content-type"] === "application/json"
            ) {
              this.showResults = response.data;
            }
          });
      }
    },
    reset() {
      this.$refs.form.reset();
    }
  }
};
</script>
