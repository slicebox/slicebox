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

.controller('AdminWatchDirectoriesCtrl', function($scope, $http, $modal, $q, $log) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Remove',
                action: removeObjects
            }
        ];

    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null,
        addDirectoryInProgress: false
    };
  
    // Scope functions
    $scope.loadWatchDirectoriesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/directory/list');
    };

    $scope.addDirectoryButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/addWatchDirectoryModalContent.html',
                controller: 'AddWatchDirectoryModalCtrl',
                size: 'lg'
            });

        modalInstance.result.then(function (path) {
            $scope.uiState.errorMessage = null;
            $scope.uiState.addDirectoryInProgress = true;

            var addDirectoryPromise = $http.post('/api/directory', {pathString: path});
            addDirectoryPromise.error(function(data) {
                $scope.uiState.errorMessage = data;
            });

            addDirectoryPromise.finally(function() {
                $scope.uiState.addDirectoryInProgress = false;
                $scope.callbacks.directoriesTable.reloadPage();
            });
        });
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };

    // Private functions
    function removeObjects(pathObjects) {
        var unwatchPromises = [];
        var unwatchPromise;

        $scope.uiState.errorMessage = null;

        angular.forEach(pathObjects, function(pathObject) {
            unwatchPromise = $http.delete('/api/directory/' + pathObject.id);
            unwatchPromises.push(unwatchPromise);
        });

        return $q.all(unwatchPromises);
    }
})

.controller('AddWatchDirectoryModalCtrl', function($scope, $modalInstance) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $modalInstance.close($scope.path);
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});