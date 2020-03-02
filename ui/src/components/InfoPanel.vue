<template>
  <div class="infopanel">
    <v-card>
      <v-toolbar flat dark>
        <v-toolbar-title>{{ title }}</v-toolbar-title>
      </v-toolbar>
      <v-tabs v-bind:align-with-title="true" show-arrows>
        <template v-for="(value, key) in infodata">
          <v-tab v-bind:key="key">{{ key }}</v-tab>

          <v-tab-item v-bind:key="key">
            <v-card flat>
              <v-card-text>
                <VueJsonPretty
                  v-bind:data="value"
                  v-bind:showLength="true"
                  v-bind:showLine="true"
                  v-bind:highlightMouseoverNode="true"
                ></VueJsonPretty>
              </v-card-text>
            </v-card>
          </v-tab-item>
        </template>
      </v-tabs>
    </v-card>
  </div>
</template>

<script>
import VueJsonPretty from "vue-json-pretty";

export default {
  name: "InfoPanel",
  components: {
    VueJsonPretty
  },
  props: {
    title: String,
    infodata: Object
  }
};
</script>

<!-- Add "scoped" attribute to limit CSS to this component only -->
<style scoped>
h3 {
  margin: 40px 0 0;
}
ul {
  list-style-type: none;
  list-style-position: outside;
}
li {
  /* display: inline-block; */
  margin: 0 10px;
}
</style>
