import Vue from 'vue'
import Vuex from 'vuex'
import sidebar from './modules/sidebar'
import auth from './modules/auth'

Vue.use(Vuex)

const debug = process.env.NODE_ENV !== 'production'

const store = new Vuex.Store({
    modules: {
        sidebar,
        auth
    },
    strict: debug,
})

export default store