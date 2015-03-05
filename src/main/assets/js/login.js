(function () {
   'use strict';
}());

angular.module('slicebox.login', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/login', {
    templateUrl: '/assets/partials/login.html',
    controller: 'LoginCtrl'
  });
})

.controller('LoginCtrl', function($scope, $rootScope, $location, authenticationService) {

    // reset login status
    authenticationService.clearCredentials();

    $scope.login = function () {
        $scope.uiState.loginInProgress = true;
        authenticationService.login($scope.username, $scope.password, function(response) {
            if(response.success) {
                $scope.uiState.loginInProgress = false;
                authenticationService.setCredentials($scope.username, $scope.password);
                $location.path('/');
            } else {
                $scope.appendErrorMessage(response.message);
                $scope.uiState.loginInProgress = false;
            }
        });
    };

});