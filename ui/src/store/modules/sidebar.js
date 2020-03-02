// initial state
const state = {
  sideBarOpen: false,
};

// getters
const getters = {
  getSideBar: (state) => state.sideBarOpen
}

// actions
const actions = {
  toggleSideBar({ commit }) {
    commit('toggleSideBar');
  }
}

//mutations
const mutations = {
  toggleSideBar(state) {
    // this is to implement the same as
    // <v-app-bar-nav-icon @click.stop="drawer = !drawer"></v-app-bar-nav-icon>
    // if we put sidebar inside of the app-bar component
    state.sideBarOpen = !state.sideBarOpen;
  }
}

export default {
  namespaced: true,
  state,
  getters,
  actions,
  mutations,
}
