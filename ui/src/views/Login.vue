<template>
    <v-container v-if="!isAuthenticated" class="login">
        <v-btn outlined v-on:click="login">Login with Google</v-btn>
    </v-container>
</template>

<script>
export default {
  name: "login",
  created() {
    window.gapi.load('auth2', initAuth);
         function initAuth() {
           window.gapi.auth2.init({
             client_id: '450819267403-n17keaafi8u1udtopauapv0ntjklmgrs.apps.googleusercontent.com'
           });
         }
  },
  mounted() {
     if (this.isAuthenticated) {
        this.$router.push('/');
     }
  },
  computed: {
     isAuthenticated() {
        return this.$store.getters['auth/authToken'];
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