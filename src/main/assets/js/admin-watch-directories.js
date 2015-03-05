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

.controller('AdminWatchDirectoriesCtrl', function($scope, $http, $modal, $q, $log, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Remove',
                action: confirmRemoveWatchDirectories
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addDirectoryInProgress = false;
  
    // Scope functions
    $scope.loadWatchDirectoriesPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/directorywatches');
    };

    $scope.addDirectoryButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/addWatchDirectoryModalContent.html',
                controller: 'AddWatchDirectoryModalCtrl',
                size: 'lg'
            });

        modalInstance.result.then(function (path) {
            $scope.uiState.addDirectoryInProgress = true;

            var addDirectoryPromise = $http.post('/api/directorywatches', {pathString: path});
            addDirectoryPromise.error(function(data) {
                $scope.appendErrorMessage(data);
            });

            addDirectoryPromise.finally(function() {
                $scope.uiState.addDirectoryInProgress = false;
                $scope.callbacks.directoriesTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmRemoveWatchDirectories(pathObjects) {
        var removeConfirmationText = 'Remove ' + pathObjects.length + ' watch directories?';

        return openConfirmationDeleteModal('Remove Watch Directories', removeConfirmationText, function() {
            return removeWatchDirectories(pathObjects);
        });
    }

    function removeWatchDirectories(pathObjects) {
        var unwatchPromises = [];
        var unwatchPromise;

        angular.forEach(pathObjects, function(pathObject) {
            unwatchPromise = $http.delete('/api/directorywatches/' + pathObject.id);
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