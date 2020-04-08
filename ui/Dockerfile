# Build stage 
FROM node:12-alpine as build-stage
WORKDIR /wfl_ui
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

# Production stage
FROM nginx:1.16-alpine as production-stage
LABEL maintainer="hornet@broadinsitute.org"
COPY --from=build-stage /wfl_ui/dist /usr/share/nginx/html
EXPOSE 80
CMD [ "nginx", "-g", "daemon off;" ]
