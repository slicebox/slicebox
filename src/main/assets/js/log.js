(function () {
   'use strict';
}());

angular.module('slicebox.log', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/log', {
    templateUrl: '/assets/partials/log.html',
    controller: 'LogCtrl'
  });
})

.controller('LogCtrl', function($scope, $http) {
    // Initialization
    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null
    };

    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        var loadLogPromise = $http.get('/api/log?startindex=' + startIndex + '&count=' + count);

        loadLogPromise.error(function(error) {
            $scope.uiState.errorMessage = 'Failed to load log: ' + error;
        });

        return loadLogPromise;
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };
});