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

    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        var loadLogPromise = $http.get('/api/log?startindex=' + startIndex + '&count=' + count);

        loadLogPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load log: ' + error);
        });

        return loadLogPromise;
    };

});