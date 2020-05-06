import Vue from 'vue'
import store from '../store'
import VueRouter from 'vue-router'
import Dashboard from '../views/Dashboard.vue'
import Login from '../views/Login.vue'

Vue.use(VueRouter)

const routes = [
  {
    path: '/',
    name: 'dashboard',
    component: Dashboard
  },
  {
    path: '/login',
    name: 'login',
    component: Login,
    meta: {
        public: true
    }
  },
  {
    path: '/query',
    name: 'query',
    // route level code-splitting
    // this generates a separate chunk (query.[hash].js) for this route
    // which is lazy-loaded when the route is visited.
    component: () => import(/* webpackChunkName: "query" */ '../views/Query.vue')
  },
  {
    path: '/workload',
    name: 'workload',
    // route level code-splitting
    // this generates a separate chunk (workload.[hash].js) for this route
    // which is lazy-loaded when the route is visited.
    component: () => import(/* webpackChunkName: "workload" */ '../views/Workload.vue')
  },
  {
    path: '/modules',
    name: 'modules',
    // route level code-splitting
    // this generates a separate chunk (modules.[hash].js) for this route
    // which is lazy-loaded when the route is visited.
    component: () => import(/* webpackChunkName: "modules" */ '../views/Modules.vue')
  },
  {
    path: '/error',
    name: 'error',
    component: () => import(/* webpackChunkName: "modules" */ '../views/Error.vue')
  }
]

const router = new VueRouter({
  mode: 'history',
  routes
})


router.beforeEach((to, from, next) => {
    const isPublic = to.matched.some(record => record.meta.public);
    const loggedIn = store.getters['auth/authenticated'];

    if (!isPublic && !loggedIn) {
        return next({ name: 'login' })
    }

    next()
})


export default router
