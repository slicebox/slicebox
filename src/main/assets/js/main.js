(function () {
   'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
  'ngRoute',
  'slicebox.view1',
  'slicebox.view2'
]).
config(['$routeProvider', function($routeProvider) {
  $routeProvider.otherwise({redirectTo: '/view1'});
}]);
