import axios from "axios";

const state = {
  authenticated: false,
  authToken: null
};

const getters = {
  authenticated: (state) => state.authenticated,
  authToken: (state) => state.authToken
}

const actions = {
  login({ commit }, user) {
    const token = user.getAuthResponse(true).access_token
    axios.defaults.headers.common.Authorization = `Bearer ${token}`
    commit('updateUser', user)
  },
  logout({ commit }, user) {
    axios.defaults.headers.common.Authorization = ""
    commit('updateUser', user)
    sessionStorage.clear()
  }
}

const mutations = {
  updateUser(state, user) {
    if (user && user.isSignedIn()) {
        state.authenticated = true;
        state.authToken = user.getAuthResponse(true).access_token;
    } else {
        state.authenticated = false;
        state.authToken = null;
    }
  }
}

export default {
  namespaced: true,
  state,
  getters,
  actions,
  mutations,
}
