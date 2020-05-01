const state = {
  authenticated: false,
  authToken: null
};

const getters = {
  authenticated: (state) => state.authenticated,
  authToken: (state) => state.authToken,
  authHeaders: (state) => {
    if(state.authToken) {
      return { Authorization: `Bearer ${state.authToken} `}
    }
  }
}

const actions = {
  updateUser({ commit }, user) {
    commit('updateUser', user);
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
        state.authHeaders = {};
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
