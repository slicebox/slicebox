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

.controller('AdminScpsCtrl', function($scope, $http, $mdDialog, $q, $log, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteScps
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScpInProgress = false;
  
    // Scope functions
    $scope.loadScpsPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scps');
    };

    $scope.addScpButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/addScpModalContent.html',
                controller: 'AddScpModalCtrl'
            });

        dialogPromise.then(function (scp) {
            $scope.uiState.addDirectoryInProgress = true;

            var addScpPromise = $http.post('/api/scps', scp);
            addScpPromise.error(function(data) {
                $scope.showErrorMessage(data);
            });

            addScpPromise.success(function() {
                $scope.showInfoMessage("SCP added");                
            });

            addScpPromise.finally(function() {
                $scope.uiState.addScpInProgress = false;
                $scope.callbacks.scpsTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteScps(scpObjects) {
        var deleteConfirmationText = 'Permanently delete ' + scpObjects.length + ' SCPs?';

        return openConfirmationDeleteModal('Delete SCPs', deleteConfirmationText, function() {
            return deleteScps(scpObjects);
        });
    }

    function deleteScps(scpObjects) {
        var removePromises = [];
        var removePromise;

        angular.forEach(scpObjects, function(scpObject) {
            removePromise = $http.delete('/api/scps/' + scpObject.id);
            removePromises.push(removePromise);
        });

        return $q.all(removePromises);
    }
})

.controller('AddScpModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ name: $scope.name, aeTitle: $scope.aeTitle, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});