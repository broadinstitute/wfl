import Vue from 'vue'
import App from './App.vue'
import vuetify from './plugins/vuetify';
import store from './store'
import router from './router'

Vue.config.productionTip = false

new Vue({
  // inject store to all children
  store,

  // inject vuetify to all children
  vuetify,

  router,
  render: h => h(App)
}).$mount('#app')
