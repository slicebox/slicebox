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

    userService.updateCurrentUser();
    
    $scope.uiState = {
        loginInProgress: false
    };

    $scope.login = function () {
        if ($scope.loginForm.$invalid) {
            return;
        }

        $scope.uiState.loginInProgress = true;
        userService.login($scope.username, $scope.password).then(function(response, status, headers, config) {
            $scope.uiState.loginInProgress = false;
            userService.updateCurrentUser().then(function () {
                var url = '/';
                try {
                    if ($rootScope.requestedPage) {
                        var requestedPath = new URL($rootScope.requestedPage).pathname;
                        if (requestedPath !== '/login') {
                            url = requestedPath;
                        }
                    }
                } catch (error) {}
                $location.path(url);
            });
        }, function(reason) {
            sbxToast.showInfoMessage(reason.data);
            $scope.uiState.loginInProgress = false;            
        });
    };

});