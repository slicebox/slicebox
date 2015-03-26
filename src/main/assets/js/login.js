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
    $scope.uiState.showMenu = false;
    
    $scope.login = function () {
        if ($scope.loginForm.$invalid) {
            return;
        }

        $scope.uiState.loginInProgress = true;
        authenticationService.login($scope.username, $scope.password, function(response) {
            if(response.success) {
                $scope.uiState.loginInProgress = false;
                $scope.uiState.showMenu = true;
                $scope.uiState.isAdmin = response.role !== 'USER';
                authenticationService.setCredentials($scope.username, $scope.password, response.role);
                $location.path('/');
            } else {
                $scope.appendErrorMessage(response.message);
                $scope.uiState.loginInProgress = false;
            }
        });
    };

});