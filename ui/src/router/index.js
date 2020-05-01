import Vue from 'vue'
import VueRouter from 'vue-router'
import Dashboard from '../views/Dashboard.vue'

Vue.use(VueRouter)

const routes = [
  {
    path: '/',
    name: 'dashboard',
    component: Dashboard
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
  }
]

const router = new VueRouter({
  mode: 'history',
  routes
})

export default router
