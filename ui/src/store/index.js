import Vue from 'vue'
import Vuex from 'vuex'
import createPersistedState from 'vuex-persistedstate'
import sidebar from './modules/sidebar'
import auth from './modules/auth'

Vue.use(Vuex)

const debug = process.env.NODE_ENV !== 'production'

const store = new Vuex.Store({
    modules: {
        sidebar,
        auth,
    },
    plugins: [createPersistedState({
        storage: window.sessionStorage,
    })],
    strict: debug,
})

export default store