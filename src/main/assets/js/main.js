(function () {
   'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('akkaDcm', [
  'ngRoute',
  'akkaDcm.view1',
  'akkaDcm.view2'
]).
config(['$routeProvider', function($routeProvider) {
  $routeProvider.otherwise({redirectTo: '/view1'});
}]);
