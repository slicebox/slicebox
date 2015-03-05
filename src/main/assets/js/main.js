(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ui.bootstrap',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.home',
    'slicebox.inbox',
    'slicebox.outbox',
    'slicebox.log',
    'slicebox.adminScps',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers'
]).

config(function($locationProvider, $routeProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});
})

.controller('SliceboxCtrl', function($scope, $location) {

    $scope.uiState = {
        errorMessages: []
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };

    $scope.closeErrorMessageAlert = function(errorIndex) {
        $scope.uiState.errorMessages.splice(errorIndex, 1);
    };
    $scope.appendErrorMessage = function(errorMessage) {
        $scope.uiState.errorMessages.push(errorMessage);
    };

});
