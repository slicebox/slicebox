(function () {
   'use strict';
}());

angular.module('slicebox.adminWatchDirectories', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/watchDirectories', {
        templateUrl: '/assets/partials/adminWatchDirectories.html',
        controller: 'AdminWatchDirectoriesCtrl'
    });
})

.controller('AdminWatchDirectoriesCtrl', function($scope, $http) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Remove',
                action: removeObjects
            }
        ];
  
    // Scope functions
    $scope.loadWatchDirectoriesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return [{id: '1', name: 'test'}];
    };

    // Private functions
    function removeObjects(objects) {
        
    }
});