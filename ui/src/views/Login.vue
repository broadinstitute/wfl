<template>
    <v-container v-if="!isAuthenticated" class="login">
        <v-btn outlined v-on:click="login">Login with Google</v-btn>
    </v-container>
</template>

<script>
export default {
  name: "login",
  mounted() {
    if(this.isAuthenticated()) {
      this.$router.push('/');
    }
  },
  computed: {
     isAuthenticated() {
        return this.$store.getters['auth/authenticated'];
     }
  },
  watch: {
    isAuthenticated() {
        this.$router.push('/');
    }
  },
  methods: {
       login() {
            window.gapi.auth2.getAuthInstance().signIn().then(user => {
                this.$store.dispatch('auth/updateUser', user);
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