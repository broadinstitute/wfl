module.exports = {
    publicPath: process.env.NODE_ENV === 'production'
        ? ''
        : '',
    devServer: {
        // this is to proxy traffic to port 3000
        // so we don't run into CORS issues locally
        proxy: {
            "/*": {
                target: "http://localhost:3000"
            },
            "/auth/google": {
                target: "http://localhost:3000/auth/google"
            }
        }
    },
    "transpileDependencies": [
        "vuetify"
    ]
}
