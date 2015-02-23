(function () {
   'use strict';
}());

angular.module('slicebox.adminScps', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/scps', {
        templateUrl: '/assets/partials/adminScps.html',
        controller: 'AdminScpsCtrl'
    });
})

.controller('AdminScpsCtrl', function($scope, $http, $modal, $q, $log) {
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
        addScpInProgress: false
    };
  
    // Scope functions
    $scope.loadScpsPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scp/list');
    };

    $scope.addScpButtonClicked = function() {
        var modalInstance = $modal.open({
                templateUrl: '/assets/partials/addScpModalContent.html',
                controller: 'AddScpModalCtrl',
                size: 'lg'
            });

        modalInstance.result.then(function (scp) {
            $scope.uiState.errorMessage = null;
            $scope.uiState.addDirectoryInProgress = true;

            var addScpPromise = $http.post('/api/scp', scp);
            addScpPromise.error(function(data) {
                $scope.uiState.errorMessage = data;
            });

            addScpPromise.finally(function() {
                $scope.uiState.addScpInProgress = false;
                $scope.callbacks.scpsTable.reloadPage();
            });
        });
    };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };

    // Private functions
    function removeObjects(scpObjects) {
        var removePromises = [];
        var removePromise;

        $scope.uiState.errorMessage = null;

        angular.forEach(scpObjects, function(scpObject) {
            removePromise = $http.delete('/api/scp/' + scpObject.id);
            removePromises.push(removePromise);
        });

        return $q.all(removePromises);
    }
})

.controller('AddScpModalCtrl', function($scope, $modalInstance) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $modalInstance.close({ name: $scope.name, aeTitle: $scope.aeTitle, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $modalInstance.dismiss();
    };
});