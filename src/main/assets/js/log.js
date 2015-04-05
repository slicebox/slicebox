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

.controller('LogCtrl', function($scope, $http, $q, openConfirmationDeleteModal) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: confirmDeleteLogs
            }
        ];

    $scope.callbacks = {};

    // Scope functions
    $scope.loadLogPage = function(startIndex, count) {
        var loadLogPromise = $http.get('/api/log?startindex=' + startIndex + '&count=' + count);

        loadLogPromise.error(function(error) {
            $scope.showErrorMessage('Failed to load log: ' + error);
        });

        return loadLogPromise;
    };

    // private functions
    function confirmDeleteLogs(logs) {
        var deleteConfirmationText = 'Permanently delete ' + logs.length + ' log messages?';

        return openConfirmationDeleteModal('Delete Log Messages', deleteConfirmationText, function() {
            return deleteLogs(logs);
        });
    }

    function deleteLogs(logs) {
        var deletePromises = [];
        var deletePromise;

        angular.forEach(logs, function(log) {
            deletePromise = $http.delete('/api/log/' + log.id);
            deletePromises.push(deletePromise);

            deletePromise.error(function(error) {
                $scope.showErrorMessage('Failed to delete log message: ' + error);
            });
        });

        return $q.all(deletePromises);
    }

});