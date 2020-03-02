<template>
  <v-container class="query">
    <v-container fluid fill-width>
      <h1>Query workload</h1>
      <div class="searchbar">
        <v-toolbar outlined>
          <v-text-field hide-details prepend-icon="search" single-line disabled placeholder="Currently unavailable"></v-text-field>

          <v-btn icon>
            <v-icon>mdi-dots-vertical</v-icon>
          </v-btn>
        </v-toolbar>
      </div>
    </v-container>

    <v-container fluid fill-width>
      <v-form ref="form" v-model="valid" lazy-validation @submit.prevent="validateAndPost">
        <v-row>
          <v-col cols="12" sm="6">
            <v-date-picker v-model="dates" range show-current color="#74AE43"></v-date-picker>
          </v-col>

          <v-col cols="12" sm="6">
            <v-text-field
              v-model="dateRangeText"
              label="Date range"
              prepend-icon="event"
              required
              readonly
            ></v-text-field>

            <v-select
              :items="environment"
              prepend-icon="extension"
              menu-props="auto"
              label="Select"
              single-line
              :rules="environmentRules"
              required
              v-model="environmentSelected"
            ></v-select>

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
          Total Results: {{this.totalResults}}
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
      showResults: {
        results: "You might need to login to query for workload!"
      },
      valid: true,
      dates: ["2020-01-10", "2020-01-12"],
      environment: [{ text: "gotc-dev" }],
      environmentRules: [v => !!v || "Environment is required"],
      environmentSelected: ""
    };
  },
  watch: {
    showResults: function () {
      this.queryResultsIsLoading = !this.queryResultsIsLoading
    }
  },
  computed: {
    dateRangeText() {
      return this.dates.join("   to   ");
    },
    totalResults() {
      if (typeof this.showResults.results !== "string") {
        return this.showResults.results.length
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
          .post("/api/v1/workflows", {
            environment: this.environmentSelected,
            start: new Date(this.dates[0]).toISOString(),
            end: new Date(this.dates[1]).toISOString()
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
