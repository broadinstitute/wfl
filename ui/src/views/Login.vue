<template>
    <v-container class="login">
        <v-btn outlined v-on:click="login">Login with Google</v-btn>
    </v-container>
</template>

<script>
export default {
  name: "login",
  mounted() {
    if(this.$store.getters['auth/authenticated']) {
      this.$router.push('/');
    }
  },
  methods: {
       login() {
            window.gapi.auth2.getAuthInstance().signIn().then(user => {
                this.$store.dispatch('auth/login', user).then(() => this.$router.push('/'));
            });
       }
  }
};
</script>

<style scoped>
.login {
  text-align: center;
}
</style>