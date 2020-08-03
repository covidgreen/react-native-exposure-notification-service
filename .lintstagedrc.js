module.exports = {
  "*.{js,jsx,ts,tsx}": "npm run lint:fix",
  // this has to be a function to prevent it from passing the files to tsc
  "*.{ts,tsx}": () => "npm run typecheck",
}
