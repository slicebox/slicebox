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

.controller('LogCtrl', function($scope, $http, $q, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/log/', 'log message(s)')
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        return $http.get('/api/log?startindex=' + startIndex + '&count=' + count);
    };

});