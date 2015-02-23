(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ui.bootstrap',
    'slicebox.directives',
    'slicebox.home',
    'slicebox.inbox',
    'slicebox.outbox',
    'slicebox.adminScps',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes'
]).

config(function($locationProvider, $routeProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});
})

.controller('SliceboxCtrl', function($scope, $location) {

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };

});
