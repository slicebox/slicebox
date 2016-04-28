(function () {
   'use strict';
}());

angular.module('slicebox.adminWatchDirectories', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/watchdirectories', {
        templateUrl: '/assets/partials/adminWatchDirectories.html',
        controller: 'AdminWatchDirectoriesCtrl'
    });
})

.controller('AdminWatchDirectoriesCtrl', function($scope, $http, openAddEntityModal, openDeleteEntitiesModalFunction) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/directorywatches/', 'directory watch(es)')
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadWatchDirectoriesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/directorywatches?startindex=' + startIndex + '&count=' + count);
    };

    $scope.addDirectoryButtonClicked = function() {
        openAddEntityModal('addWatchDirectoryModalContent.html', 'AddWatchDirectoryModalCtrl', '/api/directorywatches', 'Directory watch', $scope.callbacks.directoriesTable);
    };

})

.controller('AddWatchDirectoryModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ id: -1, name: $scope.name, path: $scope.path });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});