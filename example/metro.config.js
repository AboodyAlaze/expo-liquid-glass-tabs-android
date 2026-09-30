// Links the example to the package in the parent folder (live, no reinstall needed)
const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const projectRoot = __dirname;
const packageRoot = path.resolve(projectRoot, '..');
const esc = (p) => p.replace(/[/\\^$*+?.()|[\]{}]/g, '\\$&');

const config = getDefaultConfig(projectRoot);

config.watchFolders = [packageRoot];

// Resolve every library from the example only → exactly one copy of react / react-native / expo
config.resolver.nodeModulesPaths = [path.resolve(projectRoot, 'node_modules')];
config.resolver.extraNodeModules = { 'expo-liquid-glass-tabs-android': packageRoot };
config.resolver.blockList = [
  ...Array.from(config.resolver.blockList ?? []),
  new RegExp(`^${esc(path.join(packageRoot, 'node_modules'))}[/\\\\].*`),
];

module.exports = config;
