'use strict';

var exec = require('cordova/exec');
var SERVICE = 'ReelSaverNative';

exports.resolve = function (url, success, error) {
  exec(success, error, SERVICE, 'resolve', [url]);
};

exports.download = function (mediaUrl, filename, onEvent, error) {
  exec(onEvent, error, SERVICE, 'download', [mediaUrl, filename]);
};

exports.getSharedUrl = function (success, error) {
  exec(success, error, SERVICE, 'getSharedUrl', []);
};

exports.openDownloads = function (success, error) {
  exec(success, error, SERVICE, 'openDownloads', []);
};
