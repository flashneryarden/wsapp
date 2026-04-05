// PM2 ecosystem config — keeps wsapp running and restarts on crash/reboot
module.exports = {
  apps: [
    {
      name: "wsapp",
      script: "dist/index.js",
      args: "--headless",
      cwd: "C:\\src\\wsapp",
      node_args: "--experimental-vm-modules",
      autorestart: true,
      max_restarts: 50,
      restart_delay: 5000,
      watch: false,
      env: {
        NODE_ENV: "production",
      },
    },
  ],
};
