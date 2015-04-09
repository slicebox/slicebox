(function () {
   'use strict';
}());

angular.module('slicebox.adminScus', ['ngRoute'])

.config(function($routeProvider) {
    $routeProvider.when('/admin/scus', {
        templateUrl: '/assets/partials/adminScus.html',
        controller: 'AdminScusCtrl'
    });
})

.controller('AdminScusCtrl', function($scope, $http, $mdDialog, $q, $log, openConfirmationDeleteModal) {
    // Initialization
    $scope.objectActions =
        [
            {
                name: 'Delete',
                action: confirmDeleteScus
            }
        ];

    $scope.callbacks = {};

    $scope.uiState.addScuInProgress = false;
  
    // Scope functions
    $scope.loadScusPage = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/scus');
    };

    $scope.addScuButtonClicked = function() {
        var dialogPromise = $mdDialog.show({
                templateUrl: '/assets/partials/addScuModalContent.html',
                controller: 'AddScuModalCtrl'
            });

        dialogPromise.then(function (scu) {
            $scope.uiState.addDirectoryInProgress = true;

            var addScuPromise = $http.post('/api/scus', scu);
            addScuPromise.error(function(data) {
                $scope.showErrorMessage(data);
            });

            addScuPromise.success(function() {
                $scope.showInfoMessage("SCU added");                
            });

            addScuPromise.finally(function() {
                $scope.uiState.addScuInProgress = false;
                $scope.callbacks.scusTable.reloadPage();
            });
        });
    };

    // Private functions
    function confirmDeleteScus(scuObjects) {
        var deleteConfirmationText = 'Permanently delete ' + scuObjects.length + ' SCUs?';

        return openConfirmationDeleteModal('Delete SCUs', deleteConfirmationText, function() {
            return deleteScus(scuObjects);
        });
    }

    function deleteScus(scuObjects) {
        var removePromises = [];
        var removePromise;

        angular.forEach(scuObjects, function(scuObject) {
            removePromise = $http.delete('/api/scus/' + scuObject.id);
            removePromises.push(removePromise);
        });

        return $q.all(removePromises);
    }
})

.controller('AddScuModalCtrl', function($scope, $mdDialog) {

    // Scope functions
    $scope.addButtonClicked = function() {
        $mdDialog.hide({ name: $scope.name, aeTitle: $scope.aeTitle, host: $scope.host, port: $scope.port });
    };

    $scope.cancelButtonClicked = function() {
        $mdDialog.cancel();
    };
});