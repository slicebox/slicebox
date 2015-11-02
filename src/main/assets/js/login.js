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

.controller('LoginCtrl', function($scope, $rootScope, $location, sbxToast, userService) {

    $scope.uiState = {
        loginInProgress: false
    };

    $scope.login = function () {
        if ($scope.loginForm.$invalid) {
            return;
        }

        $scope.uiState.loginInProgress = true;
        userService.login($scope.username, $scope.password).success(function () {
            $scope.uiState.loginInProgress = false;
            userService.updateCurrentUser().then(function () {
                var url = '/';
                try {
                    if ($rootScope.requestedPage) {
                        url = (new URL($rootScope.requestedPage)).pathname;
                    }
                } catch (error) {}
                $location.path(url);
            });
        }).error(function() {
            sbxToast.showErrorMessage(response.message);
            $scope.uiState.loginInProgress = false;            
        });
    };

});