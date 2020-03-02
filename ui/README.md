# Zero UI

This is the front-end interface of Zero, the Workflow Launcher. It is a VueJS based 
SPA (Single-Page Application) that works as an ordinary client of the Zero Server.
You could find its position in the following Diagram: 

![Zero Tech Arch](https://www.lucidchart.com/publicSegments/view/07076782-0aea-4c3b-b999-6bc413f4250a/image.png)

## Structure

```
ui
├── README.md
├── babel.config.js
├── dist/
├── node_modules/
├── package-lock.json
├── package.json
├── public/
├── src/
│   ├── App.vue
│   ├── assets/
│   ├── components/
│   ├── main.js
│   ├── plugins/
│   ├── router/
│   ├── store/
│   └── views/
└── vue.config.js
```

In the above structure:

- `dist/` folder hosts the built target of the application.
- `node_modules/` hosts the installed JS dependencies and libraries. They are ignored by git.
- `public/` hosts the template static index HTML file that will be injected.
- `package-*.json` files hold various metadata relevant to the project. This file is used to give information to npm that allows it to identify the project as well as handle the project's dependencies.
- `src/` folder hosts the source code of the UI application:
    - `App.vue` is the main Vue component and it glues all other components together.
    - `components/` hosts all reusable Vue components.
    - `main.js` helps inject some project-wide tools and plugins such as `vue-router` or `vuetify` and make them available to all sub components.
    - `plugins/` holds plugin components' settgins files.
    - `router` contains files that register the internal routing table for UI.
    - `store/` hosts state files and functions that used by `vuex`.
    - `views/` holds different views or "pages" for the single-page application. The views consume the re-usable components here.
- `vue.config.js` contains settings for the Vue applicationm, such as the proxy table for local development.

## Project setup

** Note: for any of the following commands that uses `npm`, if you prefer to run from the root 
directory of the Zero repo instead of running from within `zero/ui`, please be sure to append `--prefix=ui` 
to the npm command you run. **

### Install dependencies
When you first clone the repo, run:
```
npm install
```
to install the necessary dependencies.

### Compiles and hot-reloads for development

```
npm run serve
```

### Compiles and minifies for production

```
npm run build
```

### Lints and fixes files
```
npm run lint
```

## Development

_It makes your life easier if you start the local server while developing on the ui, since you could preview the live changes in your browser._

### Styles

This project follows and uses Material Design, especilly the Vue implementation of Material Design framework: Vuetify. Please check [their docs](https://vuetifyjs.com/en/) before adding anything to the front-end.

### Add new components or views

The development process is pretty straightforward as the above structure diagram shows. Usually you just need to create a new re-usable component under `ui/src/components`, which follows the Vue file format:

```vue
<template>
<!-- your HTML and template code -->
</template>

<script>
// your JavaScript code following Vue-
// component standards
</script>

<style>
/* your CSS styles */
</style>
```

You could either put the component you created in the `App.vue` directly, or use it in the views under `views/`. Note the views files are also components, except they are designed to be specific not reusable.

### UI states
Sometimes it's inevitable to store some states for components of UI to better control their behaviors, the state files should be added to `store/modules/` and get registered in `store/index.js`.

### Single Page Routing

The SPA application is achieved by an internal routing in UI. This is controlled by the routing tables in `router/`.

## More refernces

- VueJS: https://vuejs.org/v2/guide/
- Vuetify: https://vuetifyjs.com/en/
- Vue-router: https://router.vuejs.org/
