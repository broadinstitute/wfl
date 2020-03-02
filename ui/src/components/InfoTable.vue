<template>
<div class="infotable">
    <v-data-table
        id="data-table"
        v-bind:headers="headers"
        v-bind:items="items"
        item-key="name"
        multi-sort
        loading
        loading-text="Loading the data... Please wait (You might need to login if you haven't done so)"
        ></v-data-table>
</div>
</template>

<script>
export default {
  name: "InfoTable",
  props: {
    headers: Array,
    items: Array
  }
};
</script>

<style scoped>

</style>